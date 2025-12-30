/**
 * Mini-Transaction (MTR) 包 - 页面操作的原子性管理
 *
 * <h2>核心概念</h2>
 * <p>MTR (Mini-Transaction) 是 InnoDB 中管理页面访问和修改的核心机制。
 * 它参考了 MySQL InnoDB 的 mtr_t 实现，提供了一组页面操作的原子性保证。</p>
 *
 * <h2>主要功能</h2>
 * <ul>
 *   <li><b>自动资源管理</b>: 自动 pin/unpin 页面，防止内存泄漏</li>
 *   <li><b>原子性保证</b>: 一组页面操作要么全部成功，要么全部失败</li>
 *   <li><b>脏页追踪</b>: 记录哪些页面被修改，用于刷盘</li>
 *   <li><b>Redo Log 生成</b>: 提交时生成 redo log 记录（配合日志系统）</li>
 * </ul>
 *
 * <h2>为什么需要 MTR？</h2>
 * <p>直接使用 BufferPool 需要手动管理 pin/unpin，容易出错：</p>
 * <pre>
 * // ❌ 错误示例：忘记 unpin 导致页面永远不会被淘汰
 * BufferFrame frame = bufferPool.getPage(pageId, FetchMode.READ_EXISTING);
 * Page page = frame.getPage();
 * // ... 使用页面 ...
 * // 忘记调用 bufferPool.unpinPage(pageId, isDirty);  💥 内存泄漏！
 * </pre>
 *
 * <p>使用 MTR 后，资源管理变得简单且安全：</p>
 * <pre>
 * // ✅ 正确示例：MTR 自动管理 pin/unpin
 * try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
 *     Page page = mtr.getPage(pageId);
 *     // ... 使用页面 ...
 *     mtr.commit();
 * } // 自动 unpin，无内存泄漏
 * </pre>
 *
 * <h2>使用指南</h2>
 *
 * <h3>1. 基本使用模式</h3>
 * <pre>
 * try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
 *     // 获取页面
 *     Page page = mtr.getPage(pageId);
 *
 *     // 修改页面
 *     page.putInt(offset, value);
 *
 *     // 标记为脏页（重要！）
 *     mtr.markDirty(page);
 *
 *     // 提交
 *     mtr.commit();
 * }
 * </pre>
 *
 * <h3>2. 多页面原子操作</h3>
 * <pre>
 * // 示例：B+Tree 节点分裂需要原子性地修改 3 个页面
 * try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
 *     Page oldNode = mtr.getPage(oldNodeId);
 *     Page newNode = mtr.newPage(spaceId);
 *     Page parent = mtr.getPage(parentId);
 *
 *     // 分裂逻辑
 *     splitNode(oldNode, newNode);
 *     updateParent(parent, newNode.getPageId());
 *
 *     // 标记所有修改的页面
 *     mtr.markDirty(oldNode);
 *     mtr.markDirty(newNode);
 *     mtr.markDirty(parent);
 *
 *     // 原子提交：3 个页面的修改要么全部成功，要么全部失败
 *     mtr.commit();
 * }
 * </pre>
 *
 * <h3>3. 错误处理和回滚</h3>
 * <pre>
 * try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
 *     Page page = mtr.getPage(pageId);
 *
 *     if (!validate(page)) {
 *         // 显式回滚（也可以不调用，close() 会自动回滚）
 *         mtr.rollback();
 *         return;
 *     }
 *
 *     page.putInt(offset, value);
 *     mtr.markDirty(page);
 *     mtr.commit();
 * } catch (IOException e) {
 *     // 异常发生时，try-with-resources 自动回滚
 *     log.error("Operation failed", e);
 * }
 * </pre>
 *
 * <h3>4. 虚拟线程友好</h3>
 * <pre>
 * // MTR 在虚拟线程中使用是安全的
 * ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
 *
 * executor.submit(() -> {
 *     try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
 *         Page page = mtr.getPage(pageId);
 *         // ... 操作 ...
 *         mtr.commit();
 *     } catch (IOException e) {
 *         // 处理异常
 *     }
 * });
 * </pre>
 *
 * <h2>重要规则</h2>
 * <ol>
 *   <li><b>始终使用 try-with-resources</b>: 确保资源被正确释放</li>
 *   <li><b>修改后必须 markDirty</b>: 否则修改不会被持久化</li>
 *   <li><b>一个 MTR 只在一个线程使用</b>: MTR 不是线程安全的</li>
 *   <li><b>commit 或 rollback</b>: 必须显式选择一个，否则自动回滚</li>
 *   <li><b>不要跨 MTR 使用 Page 对象</b>: Page 只在 MTR 生命周期内有效</li>
 * </ol>
 *
 * <h2>性能考虑</h2>
 * <ul>
 *   <li><b>MTR 应该尽量短小</b>: 长时间持有 MTR 会增加页面 pin 时间</li>
 *   <li><b>批量操作应该在一个 MTR 中</b>: 减少 MTR 创建/销毁开销</li>
 *   <li><b>重复获取同一页面</b>: MTR 会缓存，不会重复从 BufferPool 获取</li>
 * </ul>
 *
 * <h2>与 InnoDB 的对应关系</h2>
 * <table border="1">
 *   <tr>
 *     <th>InnoDB</th>
 *     <th>MiniDB</th>
 *     <th>说明</th>
 *   </tr>
 *   <tr>
 *     <td>mtr_t</td>
 *     <td>MiniTransaction</td>
 *     <td>核心 MTR 结构</td>
 *   </tr>
 *   <tr>
 *     <td>mtr_start()</td>
 *     <td>new MiniTransaction()</td>
 *     <td>开始 MTR</td>
 *   </tr>
 *   <tr>
 *     <td>mtr_commit()</td>
 *     <td>mtr.commit()</td>
 *     <td>提交 MTR</td>
 *   </tr>
 *   <tr>
 *     <td>mtr_memo</td>
 *     <td>memo (List&lt;MemoSlot&gt;)</td>
 *     <td>记录获取的页面</td>
 *   </tr>
 *   <tr>
 *     <td>mtr_log</td>
 *     <td>redoLogBuffer</td>
 *     <td>Redo log 缓冲</td>
 *   </tr>
 *   <tr>
 *     <td>mtr_memo_release()</td>
 *     <td>close() / rollback()</td>
 *     <td>释放资源</td>
 *   </tr>
 * </table>
 *
 * <h2>后续扩展</h2>
 * <p>当前实现是简化版本，后续需要扩展：</p>
 * <ul>
 *   <li>完整的 Redo Log 生成和写入</li>
 *   <li>MTR 级别的锁管理（X-latch, S-latch）</li>
 *   <li>支持嵌套 MTR（sub-mtr）</li>
 *   <li>MTR 统计信息（持有时间、页面数等）</li>
 *   <li>与 Transaction 系统集成</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 * @see cn.zhangyis.minidb.storage.mtr.MiniTransaction
 * @see cn.zhangyis.minidb.storage.buffer.BufferPool
 * @see cn.zhangyis.minidb.storage.page.Page
 */
package cn.zhangyis.minidb.storage.mtr;
