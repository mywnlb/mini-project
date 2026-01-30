# B+Tree 实现要点

## 1. 核心不变量

### 1.1 页内结构不变量

| 不变量 | 描述 | 违反后果 |
|--------|------|----------|
| 记录链完整性 | Infimum → 用户记录 → Supremum 单链表无断链 | 搜索遗漏、崩溃 |
| Page Directory 一致性 | 每个槽指向的记录 n_owned 累计等于槽范围内记录数 | 二分搜索失败 |
| 堆顶指针正确性 | PAGE_HEAP_TOP 始终指向下一条可分配位置 | 空间分配覆盖 |
| 记录计数准确 | PAGE_N_RECS 等于实际用户记录数 | 遍历异常 |
| 空间计算一致 | freeSpace = heapTop - lastSlotOffset | 插入失败或溢出 |

### 1.2 树结构不变量

| 不变量 | 描述 | 违反后果 |
|--------|------|----------|
| 平衡性 | 从根到任意叶子路径长度相同 | 搜索时间不可预测 |
| 有序性 | 所有键在叶子节点按序排列 | 搜索结果错误 |
| 填充因子 | 非根节点至少半满 | 树高度膨胀 |
| 父子指针一致 | 父节点分隔键与子页面键范围匹配 | 搜索定位错误 |
| 叶子链表连续 | prev/next 指针形成连续双向链表 | 范围扫描中断 |

### 1.3 并发不变量

| 不变量 | 描述 | 违反后果 |
|--------|------|----------|
| Latch 顺序 | 从上到下、从左到右获取 | 死锁 |
| SMO 原子性 | 结构修改操作（分裂/合并）对其他事务原子可见 | 脏读/幻读 |
| 写锁独占 | 修改页面前必须持有 X-Latch | 数据竞争 |

## 2. 页面物理布局

### 2.1 IndexPage 布局 (16KB)

```
Offset   Size   Field
──────────────────────────────────────────
0        38     FIL Header
         4      - checksum
         4      - pageNo
         4      - prevPageNo
         4      - nextPageNo
         8      - LSN
         4      - spaceId
         2      - pageType
         8      - flushLSN
38       56     Page Header
         2      - PAGE_N_DIR_SLOTS (槽数量)
         2      - PAGE_HEAP_TOP (堆顶位置)
         2      - PAGE_N_HEAP (堆中记录数, 含已删除)
         2      - PAGE_FREE (空闲链表头)
         2      - PAGE_GARBAGE (碎片字节数)
         2      - PAGE_LAST_INSERT (最后插入位置)
         2      - PAGE_DIRECTION (插入方向)
         2      - PAGE_N_DIRECTION (同方向插入计数)
         2      - PAGE_N_RECS (有效用户记录数)
         8      - PAGE_MAX_TRX_ID
         2      - PAGE_LEVEL (B+Tree层级, 叶子=0)
         8      - PAGE_INDEX_ID (索引ID)
94       13     Infimum Record (最小虚拟记录)
107      13     Supremum Record (最大虚拟记录)
120      ...    User Records (向下增长 ↓)
...      ...    Free Space
...      ...    Page Directory (向上增长 ↑, 每槽2字节)
16376    8      FIL Trailer
──────────────────────────────────────────
```

### 2.2 记录格式

```
┌─────────────────────────────────────────────────────┐
│                 Record Header (5 bytes)              │
│  ┌────────────────────────────────────────────────┐ │
│  │ info_bits (4 bits) │ n_owned (4 bits)          │ │
│  │ heap_no (13 bits)  │ record_type (3 bits)      │ │
│  │ next_record (16 bits, 相对偏移)                 │ │
│  └────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────┤
│                 Record Body                          │
│  ┌────────────────────────────────────────────────┐ │
│  │ [非叶子] child_page_no (4 bytes)               │ │
│  │ [叶子] key_data + value_data                   │ │
│  └────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────┘
```

### 2.3 Page Directory

- 每个槽 2 字节，存储记录偏移量
- 槽 0 指向 Infimum，最后一个槽指向 Supremum
- 每个槽"拥有" 1-8 条记录
- 用于页内二分搜索

## 3. 关键算法实现细节

### 3.1 搜索算法 (两阶段)

**阶段 1: Page Directory 二分搜索**
```
low = 0, high = slotCount - 1
while low < high:
    mid = low + (high - low + 1) / 2
    midRecOffset = slot[mid]
    cmp = compare(searchKey, keyAt(midRecOffset))
    if cmp >= 0:
        low = mid
    else:
        high = mid - 1
targetSlot = low
```

**阶段 2: 线性扫描**
```
从 slot[targetSlot-1] 指向的记录开始
沿 next_record 链表向后扫描
直到找到 >= searchKey 的记录或到达 slot[targetSlot] 的记录
```

### 3.2 插入算法

```
1. 搜索定位插入位置 (predecessor记录)
2. 检查空间:
   requiredSpace = recordLen + 2 (slot size)
   if freeSpace < requiredSpace:
       return NEED_SPLIT
3. 分配记录空间:
   newOffset = heapTop
   heapTop += recordLen
4. 写入记录:
   - 设置 next_record 指向 predecessor 的下一条
   - 修改 predecessor 的 next_record 指向新记录
5. 更新 Page Directory:
   - 增加所属槽的 n_owned
   - 如果 n_owned > 8, 分裂槽
6. 更新计数器:
   - PAGE_N_RECS++
   - PAGE_N_HEAP++
```

### 3.3 页面分裂算法

```
1. 计算分裂点:
   splitIndex = recordCount / 2
   splitKey = records[splitIndex].key

2. 分配新页面:
   newPage = allocPage()
   initPageHeader(newPage, level, indexId)

3. 迁移记录:
   for i = splitIndex to recordCount:
       record = readRecord(oldPage, i)
       insertRecord(newPage, record)

4. 截断原页面:
   修改 records[splitIndex-1].next 指向 Supremum
   重建 Page Directory
   更新 PAGE_N_RECS

5. 更新叶子链表:
   newPage.prev = oldPage
   newPage.next = oldPage.next
   oldPage.next.prev = newPage  // 如果存在
   oldPage.next = newPage

6. 返回分裂键供父节点插入
```

### 3.4 分裂传播

```
从叶子节点向上传播:
for level = 0 to height-1:
    parentPage = path[level + 1]

    if parentPage == null:
        // 到达根节点，创建新根
        createNewRoot(splitKey, oldRootPageNo, newPageNo)
        break

    // 构建节点指针记录: [splitKey | newPageNo]
    nodeRecord = buildNodePtrRecord(splitKey, newPageNo)

    if parentPage.hasSpace(nodeRecord):
        insertIntoPage(parentPage, nodeRecord)
        break
    else:
        // 父节点也需要分裂
        splitResult = splitPage(parentPage, nodeRecord)
        继续向上传播
```

## 4. 并发控制实现

### 4.1 锁类型

| 锁类型 | 用途 | 实现 |
|--------|------|------|
| S-Latch (共享) | 读取页面 | ReentrantReadWriteLock.readLock() |
| X-Latch (排他) | 修改页面 | ReentrantReadWriteLock.writeLock() |

### 4.2 Latch Coupling 实现

```java
// 搜索时的蟹行协议
PageId current = rootPageId;
BufferFrame currentFrame = getPage(current);
currentFrame.readLock();

while (true) {
    int level = readLevel(currentFrame);

    if (level == 0) {
        // 叶子节点，保持锁并返回
        return searchInLeaf(currentFrame, searchKey);
    }

    // 找到子节点
    int childPageNo = findChild(currentFrame, searchKey);
    PageId childId = new PageId(spaceId, childPageNo);

    // 关键: 先获取子节点锁
    BufferFrame childFrame = getPage(childId);
    childFrame.readLock();

    // 再释放父节点锁
    currentFrame.readUnlock();

    // 移动到子节点
    currentFrame = childFrame;
}
```

### 4.3 乐观插入策略

```
1. 使用读锁搜索到叶子节点
2. 释放路径上所有锁
3. 获取叶子节点写锁
4. 检查是否有足够空间:
   - 有空间: 插入并返回
   - 无空间: 释放锁，回退到悲观模式
```

### 4.4 悲观插入策略

```
1. 从根节点开始获取写锁路径
2. 对于每个节点:
   - 获取写锁
   - 检查是否"安全" (有足够空间，不会分裂)
   - 如果安全: 释放祖先节点的锁
3. 在叶子节点插入
4. 如需分裂，利用已持有的祖先锁完成
```

## 5. 与存储层交互

### 5.1 BufferPool 交互

```java
// 获取页面 (自动 pin)
BufferFrame frame = bufferPool.getPage(pageId, FetchMode.READ_EXISTING);

// 加锁
frame.readLock();  // 或 frame.writeLock();

try {
    ByteBuffer buf = frame.buffer();
    // 读写操作...

    if (modified) {
        frame.setDirty(true);
    }
} finally {
    frame.readUnlock();  // 或 frame.writeUnlock();
}
// unpin 由 MTR 或显式调用处理
```

### 5.2 MTR 集成

```java
try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
    // 所有页面操作通过 MTR
    BufferFrame frame = mtr.getPage(pageId);
    frame.writeLock();

    try {
        // 修改操作
        IndexPageOps.insertRecord(frame, record, insertAfter, mtr);

        // MTR 自动追踪修改
    } finally {
        frame.writeUnlock();
    }

    mtr.commit();  // 原子提交，生成 redo log
}
```

### 5.3 IndexPageOps 写操作模式

所有写操作必须通过 IndexPageOps + MTR:

```java
// 正确方式
IndexPageOps.setRecordNext(frame, offset, nextOffset, mtr);
IndexPageOps.setSlotValue(frame, slotNo, recordOffset, mtr);
IndexPageOps.incrementRecordCount(frame, mtr);

// 错误方式 (绕过 MTR)
frame.buffer().putShort(offset, value);  // 不会记录 redo log!
```

## 6. 代码组织

### 6.1 包结构

```
cn.zhangyis.minidb.storage.btree/
├── 基础组件
│   ├── RecordComparator.java      # 键比较器接口
│   ├── IntKeyComparator.java      # 整数键比较器
│   └── SimpleRecordBuilder.java   # 记录构建工具
├── 单页操作
│   ├── PageSearch.java            # 页内搜索
│   ├── PageInsert.java            # 页内插入
│   ├── PageDelete.java            # 页内删除
│   ├── PageSplit.java             # 页分裂
│   └── PageMerge.java             # 页合并/重分布
├── B+Tree 核心
│   ├── BTree.java                 # 基础 B+Tree
│   ├── BTreeMetadata.java         # 元数据
│   ├── BTreePath.java             # 搜索路径
│   └── BTreeSearchResult.java     # 搜索结果
├── 并发控制
│   ├── LatchHolder.java           # Latch 持有器
│   ├── LatchMode.java             # Latch 模式枚举
│   ├── ConcurrentBTree.java       # 并发 B+Tree
│   └── ConcurrentBTreeOps.java    # 并发操作实现
├── 游标扫描
│   ├── BTreeCursor.java           # 游标
│   ├── BTreeRangeScanner.java     # 范围扫描器
│   ├── RangeBound.java            # 范围边界
│   └── ScanEntry.java             # 扫描条目
└── 辅助工具
    ├── BTreeStats.java            # 统计信息
    ├── BTreeDiagnostics.java      # 诊断工具
    └── BTreeBulkLoader.java       # 批量加载
```

### 6.2 类职责划分

| 类 | 职责 | 是否持有状态 |
|----|------|-------------|
| IndexPage | 只读页面包装 | 否 (包装 BufferFrame) |
| IndexPageOps | 静态页面操作 | 否 |
| PageSearch/Insert/Delete | 静态单页算法 | 否 |
| BTree | 多层树操作 | 是 (metadata, comparator) |
| ConcurrentBTree | 并发安全封装 | 是 (统计计数器) |
| MiniTransaction | 页面事务管理 | 是 (memo, state) |

## 7. 性能特征

### 7.1 时间复杂度

| 操作 | 时间复杂度 | I/O 次数 |
|------|-----------|----------|
| 点查询 | O(log N) | 树高度 h |
| 插入 (无分裂) | O(log N) | h |
| 插入 (有分裂) | O(log N) | h + 分裂级数 |
| 删除 | O(log N) | h |
| 范围扫描 | O(log N + M) | h + M/每页记录数 |

### 7.2 空间开销

| 项目 | 开销 |
|------|------|
| 页头 | 94 字节/页 |
| Infimum/Supremum | 26 字节/页 |
| 记录头 | 5 字节/记录 |
| Page Directory | 2 字节/槽 (每4-8条记录1槽) |
| 内部节点指针 | 4 字节/子页面 |

### 7.3 典型配置

| 参数 | 推荐值 | 说明 |
|------|--------|------|
| 页面大小 | 16KB | InnoDB 标准 |
| 填充因子 | 70-90% | 留余量减少分裂 |
| 批量加载填充 | 90% | 紧凑但留少量空间 |
| 乐观/悲观阈值 | 根据分裂率调整 | 分裂率 > 10% 时考虑悲观优先 |

## 8. 关键实现检查清单

### 8.1 插入操作检查

- [ ] 搜索定位正确 (找到 predecessor)
- [ ] 空间检查包含槽空间
- [ ] 记录链表更新原子
- [ ] Page Directory 更新完整
- [ ] 计数器同步更新
- [ ] MTR 包裹所有写操作

### 8.2 分裂操作检查

- [ ] 分裂点计算正确 (大约半分)
- [ ] 新页面初始化完整
- [ ] 记录迁移无遗漏
- [ ] 原页面截断正确
- [ ] 叶子链表指针更新
- [ ] 父节点指针插入
- [ ] 根分裂特殊处理

### 8.3 并发操作检查

- [ ] 锁获取顺序一致 (上→下, 左→右)
- [ ] 没有锁泄漏 (finally 释放)
- [ ] 乐观失败正确回退
- [ ] SMO 期间持有必要锁
- [ ] 读操作使用读锁
- [ ] 写操作使用写锁

## 9. 常见陷阱

### 9.1 偏移量计算错误

```java
// 错误: 混淆绝对偏移和相对偏移
int next = buffer.getShort(recordOffset + NEXT_OFFSET);  // 相对偏移!
int actualNext = recordOffset + next;  // 需要加上基址

// 正确做法: 使用工具方法
int nextOffset = IndexPageLayout.readRecordNext(buffer, recordOffset);
```

### 9.2 Page Directory 槽分裂遗漏

```java
// 错误: 只更新 n_owned，忘记分裂
slot.nOwned++;
if (slot.nOwned > 8) {
    // 必须分裂槽!
    splitSlot(frame, slotNo, mtr);
}
```

### 9.3 叶子链表断链

```java
// 错误: 只更新单向
newPage.setNext(oldPage.getNext());
oldPage.setNext(newPage);

// 正确: 双向都要更新
int oldNext = oldPage.getNext();
newPage.setNext(oldNext);
newPage.setPrev(oldPage.getPageNo());
oldPage.setNext(newPage.getPageNo());
if (oldNext != 0) {
    oldNextPage.setPrev(newPage.getPageNo());
}
```

### 9.4 MTR 提交前释放锁

```java
// 错误: 提交前释放锁可能导致其他事务看到不一致状态
frame.writeUnlock();
mtr.commit();

// 正确: 先提交再释放
mtr.commit();
frame.writeUnlock();
```

## 10. 测试要点

### 10.1 边界测试

- 空页面插入第一条记录
- 页面满时分裂
- 删除最后一条记录
- 根节点分裂
- 连续分裂传播到根

### 10.2 并发测试

- 多线程同时插入不同键
- 多线程同时插入相同键
- 读写混合并发
- 分裂过程中的并发搜索

### 10.3 恢复测试

- 插入中途崩溃
- 分裂中途崩溃
- 删除中途崩溃
- 重启后数据完整性验证
