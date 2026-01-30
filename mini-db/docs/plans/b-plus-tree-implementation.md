# B+ Tree 实现计划

## 一、现有基础设施

项目已具备：
- **Page 层**: IndexPage/IndexPageLayout/IndexPageOps (页面读写)
- **Buffer Pool**: 页面缓存、LRU 淘汰、脏页管理
- **MTR**: Mini-Transaction，保证原子性和 WAL
- **记录格式**: Compact 行格式 (CompactRecordUtil)

---

## 二、禁用的实现路径

### 1. ❌ "单次分裂 = 一个 MTR 覆盖 3~4 个页面修改" 作为硬约束

**为什么危险**：
- 把"分裂 + 递归父分裂 + 根分裂"全部打包进一个 MTR 会导致：
  - latch 持有时间不可控（长链路 X-latch），并发性能崩溃
  - 失败回滚/重试成本爆炸
  - 后续引入 page-level redo logging / page reorg 时边界会被打破

**失败类型**: 并发性能崩溃、恢复复杂度爆炸

**正确做法**: MTR 边界改成"每个被修改页的物理变更在 MTR 内原子"，SMO 用更上层的"结构变更协议"保证可恢复一致性。

### 2. ❌ 删除只做 delete-mark + 立即复用 PAGE_FREE 作为完成态

**为什么危险**：
- delete-mark 是"中间态"，真正从链表摘除并进入垃圾链表是 purge 阶段完成后才发生
- 立即复用空间会造成读视图下的可见性与物理复用冲突

**失败类型**: 静默数据错误（MVCC 可见性破坏）

**正确做法**: 两阶段删除
1. 逻辑删除：只做 delete-mark（不从正常链表摘除，不进 PAGE_FREE）
2. 物理清理：purge 阶段确定无读视图需要后，再摘链并挂入 PAGE_FREE

---

## 三、核心不变量（实现中必须处处引用）

### 不变量 1: 页内结构一致性

以下字段必须同时保持一致：
- 记录链（Infimum → rec1 → rec2 → ... → Supremum）
- Page Directory 槽数组
- PAGE_N_RECS（记录数）
- PAGE_FREE（空闲链表头）
- PAGE_HEAP_TOP（堆顶位置）

**违反后果**: 页内遍历错误、记录丢失

### 不变量 2: 叶子层双向链表一致性

任何可恢复点，叶子层 prev/next 链表不能出现：
- 断链（某页的 next 指向的页的 prev 不指回来）
- 环链（非预期的循环引用）
- 悬空指针（指向未初始化或已释放的页）

**违反后果**: 范围扫描丢失数据或死循环

### 不变量 3: SMO 可恢复一致性

任意崩溃恢复后，必须满足以下之一：
- **状态 A**: 新页完全不可达（父未插入分隔键）且不破坏叶子链
- **状态 B**: 新页可达且父分隔键与子指针一致

**绝对禁止**: "父已指向新页但新页内容/链表未完成"的半成品状态

**违反后果**: 恢复后树结构损坏、数据不可访问

### 不变量 4: 父子指针一致性

对于非叶子节点的每条记录 `(separator_key, child_page_no)`：
- child 页中所有记录的 key >= separator_key
- child 页中所有记录的 key < 下一条记录的 separator_key（或 +∞）

**违反后果**: 查找路由错误、数据丢失

### 不变量 5: 根页元数据一致性

`IndexMeta.rootPageNo` 必须始终指向有效的根页：
- 根分裂时，新根创建完成后才能更新元数据
- 元数据更新必须生成 redo，保证恢复后一致

**违反后果**: 恢复后整棵树不可访问

---

## 四、核心数据结构设计

### 1. B+树节点布局 (复用 IndexPage)

```
叶子节点 (Leaf Page):
┌─────────────────────────────────────┐
│ FIL Header (38B)                    │
│ Page Header (56B)                   │
│   - level = 0 (叶子层)              │
│   - prev_page / next_page (双向链)  │
│ Infimum → rec1 → rec2 → ... → Supremum │
│ User Records: [key | value/rowid]   │
│ Page Directory (槽数组)             │
│ FIL Trailer (8B)                    │
└─────────────────────────────────────┘

非叶子节点 (Internal Page):
┌─────────────────────────────────────┐
│ FIL Header (38B)                    │
│ Page Header (56B)                   │
│   - level > 0                       │
│   - 特殊: 第一条用户记录携带 leftmost_child │
│ Infimum → rec1 → rec2 → ... → Supremum │
│ User Records: [key | child_page_no] │
│ Page Directory                      │
│ FIL Trailer (8B)                    │
└─────────────────────────────────────┘
```

### 2. 关键类设计

```java
// 索引元数据（持久化）
public class IndexMeta {
    private final long indexId;
    private final int spaceId;
    private int rootPageNo;        // 持久化到字典页
    private int treeHeight;        // 可选，加速

    // 必须通过 MTR 更新，生成 redo
    public void updateRoot(int newRootPageNo, MiniTransaction mtr);
}

// B+树主类
public class BPlusTree {
    private final IndexMeta meta;
    private final BufferPool bufferPool;
    private final RecordComparator comparator;
    private final RecordFormat recordFormat;
}

// 游标，用于遍历和定位
public class BTreeCursor {
    private PageId pageId;
    private int recordOffset;
    private LatchMode latchMode;
    private List<PageId> latchedPages;  // 持有的 latch 栈
}

// 搜索结果
public class SearchResult {
    private PageId leafPageId;
    private int recordOffset;
    private boolean exactMatch;
    private List<PathEntry> searchPath;  // 悲观模式需要
}

// 路径条目（用于悲观插入/删除）
public class PathEntry {
    private PageId pageId;
    private int recordOffset;  // 指向子页的记录位置
}
```

---

## 五、核心算法实现要点

### 1. 查找算法 (Search)

```
search(key, mode):
  1. 从 IndexMeta 获取 rootPageNo
  2. 获取根页 latch (S-latch for read, X-latch for write)
  3. 在当前页用二分查找定位 (利用 Page Directory)
  4. 如果是非叶子节点:
     - 找到 key 所在区间对应的 child_page_no
     - 获取子页 latch
     - 根据"安全"判定决定是否释放父 latch
     - 递归进入子页
  5. 如果是叶子节点:
     - 返回 SearchResult(pageId, recordOffset, exactMatch, path)
```

**实现要点**:
- 利用 `IndexPageLayout.slotOffset()` 实现快速二分
- 使用 `CompactRecordUtil.getNextRecordOffset()` 遍历记录链
- 比较器需支持前缀匹配
- 搜索时跳过 Infimum/Supremum

### 2. 插入算法 (Insert)

#### 2.1 乐观插入

```
optimistic_insert(key, value):
  1. search(key, LATCH_LEAF_X) - 只在叶子持有 X-latch
  2. 检查叶子页空间是否足够（含 directory 扩展余量）
  3. 如果足够:
     - 在 MTR 内执行插入
     - 更新 PAGE_N_RECS, PAGE_HEAP_TOP, directory
     - commit MTR
     - 返回成功
  4. 如果不足:
     - 释放 latch
     - 转入悲观插入
```

#### 2.2 悲观插入（需要分裂）

```
pessimistic_insert(key, value):
  1. search(key, LATCH_PATH_X) - 沿路径持有 X-latch
  2. 执行叶子分裂（见下文详细时序）
  3. 如果父节点也满，递归分裂
  4. 如果根节点分裂，创建新根并更新 IndexMeta
```

#### 2.3 叶子分裂详细时序（保证不变量 2, 3）

```
leaf_split(old_page, insert_key, insert_value, parent_path):

  === MTR 1: 准备新页 ===
  mtr1.begin()
  1. 分配新页 new_page
  2. 初始化新页（Infimum/Supremum, page header, level=0）
  3. 设置 new_page.prev = old_page.page_no
  4. 设置 new_page.next = old_page.next  // 暂存，还未生效
  mtr1.commit()

  === MTR 2: 原子更新链表和移动记录 ===
  mtr2.begin()
  5. 计算分裂点（基于空间，非记录数）
  6. 移动记录到 new_page（从分裂点到 Supremum 前）
  7. 更新 old_page 的记录链（最后一条指向 Supremum）
  8. 更新 old_page.next = new_page.page_no
  9. 如果 old_next_page 存在:
     - old_next_page.prev = new_page.page_no
  10. 更新两页的 PAGE_N_RECS, directory
  mtr2.commit()

  === MTR 3: 父节点插入分隔键 ===
  mtr3.begin()
  11. separator_key = new_page 的第一条用户记录的 key
  12. parent.insert(separator_key, new_page.page_no)
  13. 如果父满，递归 internal_split()
  mtr3.commit()

  === 最后: 在正确的页插入新记录 ===
  14. 比较 insert_key 与 separator_key，选择目标页
  15. 执行插入
```

**关键点**:
- MTR 2 保证链表更新原子性（不变量 2）
- 如果 MTR 3 前崩溃：新页已初始化但不可达，不破坏树结构（不变量 3 状态 A）
- 如果 MTR 3 后崩溃：新页可达且父指针正确（不变量 3 状态 B）

#### 2.4 分裂点选择（基于空间）

```
find_split_point(page, insert_key, insert_value):
  1. 计算插入后总空间需求
  2. 从插入位置开始，向两侧扫描
  3. 目标：两页都能容纳各自记录 + directory 增长余量
  4. 对于自增主键场景：偏向右页少量搬迁
  5. 返回 split_record_offset
```

#### 2.5 分隔键规则（精确定义）

```
叶子分裂:
  - separator_key = 新右页的第一条用户记录的完整 key
  - 父记录: (separator_key, right_page_no)
  - 语义: key >= separator 走右页，key < separator 走左页

非叶子分裂:
  - 选择中间记录作为 separator
  - separator 上推到父（从当前页删除）
  - 左页保留 key < separator 的记录
  - 右页保留 key >= separator 的记录
  - 右页需要携带"最左 child 指针"（原 separator 的 child）
```

### 3. 删除算法 (Delete) - 两阶段设计

#### 3.1 逻辑删除（用户调用）

```
delete(key):
  1. search(key) 定位记录
  2. 如果找到且未被 delete-mark:
     - 在 MTR 内设置 delete_mask = 1
     - 不修改记录链，不修改 PAGE_N_RECS
     - 不加入 PAGE_FREE
  3. commit MTR
```

**注意**: 逻辑删除后，记录仍在链表中，仍占用空间，仍可被 MVCC 读取。

#### 3.2 物理清理（Purge 阶段）

```
purge(record_offset, page):
  前提: 确认无活跃事务需要读取此记录

  1. 从记录链中摘除（更新前一条记录的 next）
  2. 加入 PAGE_FREE 链表
  3. 更新 PAGE_N_RECS
  4. 更新 PAGE_GARBAGE（累计垃圾空间）
  5. 可选: 如果 PAGE_GARBAGE > 阈值，触发页重组
```

#### 3.3 页合并（延迟执行）

```
try_merge(page):
  前提: 页填充率 < MERGE_THRESHOLD (50%)

  1. 检查相邻页（prev 或 next）
  2. 如果合并后能容纳两页记录:
     - 移动记录
     - 更新链表
     - 从父节点删除分隔键
     - 释放空页
  3. 否则尝试 redistribute（借记录）
```

**当前阶段简化**: 先不实现页合并，仅记录"可合并"标记。

### 4. 范围查询 (Range Scan)

```
range_scan(low_key, high_key):
  1. search(low_key) 定位起始位置
  2. 获取叶子页 S-latch
  3. 从当前记录开始遍历:
     while true:
       - 如果当前记录是 Supremum:
         - next_page = page.next
         - 如果 next_page == FIL_NULL，结束
         - 获取 next_page S-latch
         - 释放当前页 S-latch (latch coupling)
         - 切换到 next_page
       - 如果 record.key > high_key，结束
       - 如果 record.delete_mask == 0，yield record
       - 移动到下一条记录
```

---

## 六、并发控制策略

### 1. Latch vs Lock 边界（重要澄清）

```
Latch (本计划实现):
  - 保护页内结构一致性
  - 短期持有（单次操作内）
  - S-latch: 读操作
  - X-latch: 写操作

Lock (本阶段不实现):
  - 保护事务隔离级别语义
  - 长期持有（事务生命周期）
  - 记录锁、间隙锁、next-key 锁

简化声明: 本阶段只实现 latch，不保证 RR 下幻读/next-key 语义。
后续接 MVCC/undo 时需要在扫描接口添加"锁定范围"钩子。
```

### 2. Latch Coupling (蟹行协议)

```
向下遍历时:
  1. 获取当前节点 latch
  2. 获取子节点 latch
  3. 判断子节点是否"安全"
  4. 如果安全，释放父节点 latch
  5. 如果不安全，保持父节点 latch

"安全"判定（必须与操作类型绑定）:

  查找操作:
    - 任何节点都安全（只读，不修改结构）

  插入操作:
    - 安全条件: 节点有足够空间容纳新记录
    - 需考虑: 记录大小 + directory 可能扩展 + 预留余量
    - 不安全时: 可能触发 split，需要修改父

  删除操作 (如果实现合并):
    - 安全条件: 删除后填充率 > MERGE_THRESHOLD
    - 不安全时: 可能触发 merge/redistribute，需要修改父
```

### 3. 乐观/悲观模式

```
乐观模式 (Optimistic):
  - 假设不需要结构修改 (SMO)
  - 遍历时只持有 S-latch
  - 到达叶子后升级为 X-latch
  - 如果发现需要 SMO，释放所有 latch，转悲观模式

悲观模式 (Pessimistic):
  - 预期需要结构修改
  - 从根开始持有 X-latch
  - 沿路径记录 PathEntry
  - 到达叶子后执行 SMO
```

### 4. 死锁预防

```
规则 1: 自顶向下获取 latch
  - 永远先获取父节点 latch，再获取子节点 latch
  - 禁止从子节点向上获取父节点 latch

规则 2: 同层节点按 page_no 顺序
  - 需要同时持有多个同层节点 latch 时
  - 按 page_no 升序获取

规则 3: latch coupling 释放时机
  - 确认子节点安全后立即释放父 latch
  - 不要持有不必要的 latch
```

---

## 七、根页元数据持久化

### 1. IndexMeta 存储位置

```
最小实现方案:
  - 在表空间的固定位置（如 page 0 或专用字典页）存储索引元数据
  - 格式: [index_id (8B)] [space_id (4B)] [root_page_no (4B)] [tree_height (2B)]

后续扩展:
  - 集成到系统表 (类似 InnoDB 的 SYS_INDEXES)
```

### 2. 根分裂时的元数据更新

```
root_split(old_root):
  === MTR 1: 创建新根 ===
  mtr1.begin()
  1. 分配新页作为新根
  2. 初始化新根（level = old_root.level + 1）
  3. 插入第一条记录: (min_key, old_root.page_no)
  mtr1.commit()

  === MTR 2: 分裂旧根（现在是内部节点）===
  mtr2.begin()
  4. 执行 internal_split(old_root)
  5. 新分裂出的页的分隔键插入新根
  mtr2.commit()

  === MTR 3: 更新元数据 ===
  mtr3.begin()
  6. IndexMeta.rootPageNo = new_root.page_no
  7. 生成元数据更新的 redo
  mtr3.commit()
```

---

## 八、实现阶段划分（修订版）

| 阶段 | 内容 | 依赖 | 验证点 |
|-----|------|-----|-------|
| **Phase 1** | 单页操作: 页内查找、插入 | IndexPageOps | 不变量 1 |
| **Phase 2** | 树遍历: search(), 根到叶子路径 | Phase 1 | 不变量 4 |
| **Phase 3** | 乐观插入（无分裂） | Phase 2 | 单页树正确性 |
| **Phase 4a** | 叶子分裂 + 叶子链更新 + 父插入 | Phase 3, MTR | 不变量 2, 3 |
| **Phase 4b** | 内部节点分裂 + 根分裂 + 元数据更新 | Phase 4a | 不变量 5 |
| **Phase 5** | 逻辑删除（delete-mark only） | Phase 2 | MVCC 兼容 |
| **Phase 6** | 范围扫描、游标 | Phase 2 | 叶子链遍历 |
| **Phase 7** | Purge + 页合并（可选） | Phase 5 | 空间回收 |
| **Phase 8** | 并发控制优化 | All | 并发正确性 |

---

## 九、关键实现细节

### 1. 记录格式

```
非叶子节点记录:
  [record_header (5B)] [key_fields] [child_page_no (4B)]

  特殊: 第一条用户记录可能只有 [child_page_no]（最左子树指针）

叶子节点记录:
  聚簇索引: [record_header (5B)] [key_fields] [value_fields]
  二级索引: [record_header (5B)] [key_fields] [primary_key_fields]
```

### 2. 特殊记录处理

```
Infimum:
  - offset = 94 (FIL_HEADER + PAGE_HEADER)
  - 最小虚拟记录，始终是链表头
  - next 指向第一条用户记录（或 Supremum）

Supremum:
  - offset = 107 (Infimum + 13)
  - 最大虚拟记录，始终是链表尾
  - next = 0（链表结束标记）

搜索/遍历时必须跳过这两个记录
```

### 3. Page Directory 二分查找

```
binary_search_directory(page, key):
  low = 0
  high = slot_count - 1  // slot 0 = Infimum, slot n-1 = Supremum

  while low < high:
    mid = (low + high + 1) / 2
    slot_record = get_record_at_slot(mid)
    if key < slot_record.key:
      high = mid - 1
    else:
      low = mid

  // low 指向的 slot 的记录 <= key
  // 从该 slot 的记录开始线性扫描
  return linear_search_from_slot(low, key)
```

---

## 十、测试计划

### Phase 1-3 测试

1. **页内插入测试**: 插入 100 条记录，验证链表和 directory 一致性
2. **查找测试**: 精确查找、范围边界查找
3. **满页检测**: 插入直到空间不足，验证返回"需要分裂"

### Phase 4 测试

4. **单次分裂测试**: 触发一次叶子分裂，验证：
   - 两页记录数合理
   - 叶子链表正确（prev/next）
   - 父节点分隔键正确
5. **多次分裂测试**: 插入 1000+ 记录，触发多次分裂
6. **根分裂测试**: 持续插入直到根分裂，验证新根正确
7. **崩溃恢复测试**: 在分裂各阶段模拟崩溃，验证恢复后一致性

### Phase 5-6 测试

8. **逻辑删除测试**: 删除后记录仍在链表中，delete_mask = 1
9. **范围扫描测试**: 跨多页扫描，验证结果完整性
10. **删除后扫描**: 扫描应跳过 delete-marked 记录

### Phase 8 测试

11. **并发读测试**: 多线程同时读取
12. **并发写测试**: 多线程同时插入（触发并发分裂）
13. **读写混合测试**: 读写并发，验证无死锁、无数据错误

---

## 十一、性能考虑

- **预分裂**: 批量加载时可预分裂提升效率
- **页预读**: 范围扫描时预读相邻页
- **自适应搜索**: 缓存热点页的搜索路径 (AHI) - 后续优化
- **Change Buffer**: 非唯一二级索引延迟合并 - 后续优化

---

## 十二、文件组织

```
storage/
├── btree/
│   ├── BPlusTree.java          # B+树主类，对外接口
│   ├── BTreeSearch.java        # 搜索逻辑
│   ├── BTreeInsert.java        # 插入逻辑
│   ├── BTreeSplit.java         # 分裂逻辑（叶子/内部/根）
│   ├── BTreeDelete.java        # 删除逻辑
│   ├── BTreeCursor.java        # 游标，范围扫描
│   ├── BTreeLatch.java         # Latch 管理，coupling 逻辑
│   └── RecordComparator.java   # 记录比较器
├── meta/
│   └── IndexMeta.java          # 索引元数据（含 rootPageNo）
```

---

## 十三、参考资料

- InnoDB 源码: `btr/btr0btr.cc`, `btr/btr0cur.cc`, `btr/btr0pcur.cc`
- 《MySQL技术内幕：InnoDB存储引擎》第5章
- CMU 15-445 Database Systems - B+Tree Index
-erta Graefe, "A Survey of B-Tree Locking Techniques"
