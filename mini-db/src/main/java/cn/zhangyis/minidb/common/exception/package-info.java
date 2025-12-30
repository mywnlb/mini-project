/**
 * MiniDB 异常体系
 *
 * <p>提供完整的自定义异常层次结构，用于精确表示数据库运行时的各种错误情况。</p>
 *
 * <h2>设计原则</h2>
 * <ol>
 *   <li><b>分层设计</b>: 按模块划分异常类型（存储层、Buffer Pool、MTR 等）</li>
 *   <li><b>错误码支持</b>: 每个异常都有唯一的错误码，便于程序化处理</li>
 *   <li><b>静态工厂方法</b>: 提供便捷的异常创建方法</li>
 *   <li><b>详细的上下文信息</b>: 包含足够的信息用于诊断问题</li>
 * </ol>
 *
 * <h2>异常层次结构</h2>
 * <pre>
 * {@link cn.zhangyis.minidb.common.exception.MiniDbException MiniDbException} (基类)
 *   │
 *   ├─ {@link cn.zhangyis.minidb.common.exception.StorageException StorageException} (存储层异常)
 *   │   ├─ {@link cn.zhangyis.minidb.common.exception.DiskIOException DiskIOException}
 *   │   ├─ {@link cn.zhangyis.minidb.common.exception.PageCorruptedException PageCorruptedException}
 *   │   └─ SpaceExhaustedException (Future)
 *   │
 *   ├─ {@link cn.zhangyis.minidb.common.exception.BufferException BufferException} (Buffer Pool 异常)
 *   │   ├─ {@link cn.zhangyis.minidb.common.exception.BufferExhaustedException BufferExhaustedException}
 *   │   ├─ PagePinnedException (Future)
 *   │   └─ PageNotFoundException (Future)
 *   │
 *   ├─ {@link cn.zhangyis.minidb.common.exception.MtrException MtrException} (MTR 异常)
 *   │   ├─ {@link cn.zhangyis.minidb.common.exception.MtrStateException MtrStateException}
 *   │   └─ {@link cn.zhangyis.minidb.common.exception.PageNotManagedByMtrException PageNotManagedByMtrException}
 *   │
 *   ├─ TransactionException (事务异常 - Future)
 *   │   ├─ DeadlockException
 *   │   ├─ LockTimeoutException
 *   │   └─ TransactionAbortedException
 *   │
 *   └─ SqlException (SQL 异常 - Future)
 *       ├─ ParseException
 *       ├─ SemanticException
 *       └─ ExecutionException
 * </pre>
 *
 * <h2>错误码设计</h2>
 * <p>错误码采用 6 位数字格式：<code>MMTTSS</code></p>
 * <ul>
 *   <li><code>MM</code> - 模块代码（10=Storage, 20=Buffer, 30=MTR, ...）</li>
 *   <li><code>TT</code> - 错误类型（01=IO, 02=Corruption, ...）</li>
 *   <li><code>SS</code> - 序号（同一类型内的不同错误）</li>
 * </ul>
 *
 * <h3>模块代码分配</h3>
 * <table border="1">
 *   <tr><th>代码</th><th>模块</th></tr>
 *   <tr><td>10</td><td>Storage Layer (存储层)</td></tr>
 *   <tr><td>20</td><td>Buffer Pool (缓冲池)</td></tr>
 *   <tr><td>30</td><td>Mini-Transaction (MTR)</td></tr>
 *   <tr><td>40</td><td>Transaction System (事务系统)</td></tr>
 *   <tr><td>50</td><td>SQL Layer (SQL 层)</td></tr>
 *   <tr><td>60</td><td>Execution Engine (执行引擎)</td></tr>
 *   <tr><td>70</td><td>Catalog (元数据)</td></tr>
 * </table>
 *
 * <h2>使用示例</h2>
 *
 * <h3>示例 1: 抛出异常</h3>
 * <pre>
 * // 使用静态工厂方法（推荐）
 * throw DiskIOException.readEOF(pageNo);
 *
 * // 直接构造
 * throw new DiskIOException(DiskIOException.ERR_READ_EOF,
 *     "Unexpected EOF reading page: " + pageNo);
 * </pre>
 *
 * <h3>示例 2: 捕获异常</h3>
 * <pre>
 * try {
 *     page = diskManager.readPage(pageId);
 * } catch (DiskIOException e) {
 *     // 处理磁盘 I/O 错误
 *     log.error("Disk I/O error [{}]: {}", e.getErrorCode(), e.getMessage());
 *     if (e.getErrorCode() == DiskIOException.ERR_READ_EOF) {
 *         // 特殊处理 EOF 错误
 *     }
 * } catch (PageCorruptedException e) {
 *     // 处理页面损坏
 *     log.error("Page corrupted: {}", e.getMessage());
 * } catch (StorageException e) {
 *     // 处理其他存储层错误
 *     log.error("Storage error: {}", e.getMessage());
 * }
 * </pre>
 *
 * <h3>示例 3: 按模块捕获</h3>
 * <pre>
 * try {
 *     mtr.commit();
 * } catch (MtrException e) {
 *     // 所有 MTR 相关的异常
 *     log.error("MTR error [module={}]: {}",
 *         e.getModuleCode(), e.getMessage());
 * } catch (BufferException e) {
 *     // 所有 Buffer Pool 相关的异常
 *     log.error("Buffer error: {}", e.getMessage());
 * } catch (MiniDbException e) {
 *     // 所有 MiniDB 异常
 *     log.error("Database error: {}", e.getMessage());
 * }
 * </pre>
 *
 * <h2>最佳实践</h2>
 * <ol>
 *   <li><b>优先使用静态工厂方法</b>: 提供更好的可读性和类型安全</li>
 *   <li><b>包含原始异常</b>: 使用 cause 参数保留异常链</li>
 *   <li><b>提供上下文信息</b>: 在消息中包含 pageId、fileName 等关键信息</li>
 *   <li><b>按层次捕获</b>: 从具体到抽象捕获异常</li>
 *   <li><b>记录错误码</b>: 在日志中输出错误码便于问题追踪</li>
 * </ol>
 *
 * <h2>扩展指南</h2>
 * <p>添加新的异常类型时：</p>
 * <ol>
 *   <li>确定所属模块，选择合适的父类</li>
 *   <li>分配错误码（检查是否冲突）</li>
 *   <li>定义错误码常量</li>
 *   <li>提供静态工厂方法</li>
 *   <li>编写完整的 Javadoc</li>
 * </ol>
 *
 * @author MiniDB
 * @version 1.0
 * @see cn.zhangyis.minidb.common.exception.MiniDbException
 */
package cn.zhangyis.minidb.common.exception;
