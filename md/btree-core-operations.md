# B+Tree 核心操作

## 1. 搜索操作

### 1.1 精确搜索

```java
// 搜索单个键
BTreeSearchResult result = btree.search(searchKey, mtr);

if (result != null && result.isExactMatch()) {
    byte[] recordData = result.getRecordData();
    PageId pageId = result.getPageId();
    int recordOffset = result.getRecordOffset();
}
```

### 1.2 搜索算法

```
算法: B+Tree 搜索
输入: searchKey
输出: BTreeSearchResult

1. currentPage = rootPage
2. while currentPage.level > 0:
      // 内部节点：二分搜索找到子节点
      childIndex = binarySearch(currentPage, searchKey)
      currentPage = getChildPage(currentPage, childIndex)
3. // 叶子节点：二分搜索找到记录
4. recordIndex = binarySearch(currentPage, searchKey)
5. if exactMatch:
      return SearchResult(found=true, page, offset, data)
   else:
      return SearchResult(found=false, page, insertPosition)
```

### 1.3 页面内二分搜索

```java
public class PageSearch {
    /**
     * 在页面内二分搜索
     * @return 找到返回索引，未找到返回 -(insertionPoint + 1)
     */
    public static int binarySearch(ByteBuffer buffer, byte[] searchKey,
                                   RecordComparator comparator) {
        int low = 0;
        int high = getRecordCount(buffer) - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            byte[] midKey = getKeyAt(buffer, mid);
            int cmp = comparator.compareKeys(midKey, searchKey);

            if (cmp < 0) {
                low = mid + 1;
            } else if (cmp > 0) {
                high = mid - 1;
            } else {
                return mid; // 找到
            }
        }
        return -(low + 1); // 未找到，返回插入点
    }
}
```

## 2. 插入操作

### 2.1 基本插入

```java
// 插入记录
byte[] record = SimpleRecordBuilder.buildRecord(key, value, valueLen);
boolean success = btree.insert(record, searchKey, mtr);
```

### 2.2 插入算法

```
算法: B+Tree 插入
输入: record, searchKey
输出: boolean (成功/失败)

1. path = searchPath(searchKey)  // 记录从根到叶子的路径
2. leafPage = path.getLeafPage()
3. if leafPage.hasSpace(record):
      insertIntoPage(leafPage, record)
      return true
4. else:
      // 需要分裂
      splitResult = splitPage(leafPage, record)
      propagateSplit(path, splitResult)
      return true
```

### 2.3 页面内插入

```java
public class PageInsert {
    public static boolean insert(ByteBuffer buffer, byte[] record,
                                 int insertPos, RecordComparator comparator) {
        int recordSize = record.length;
        int freeSpace = getFreeSpace(buffer);

        if (freeSpace < recordSize + SLOT_SIZE) {
            return false; // 空间不足
        }

        // 1. 分配记录空间（从页面末尾向前）
        int dataOffset = getDataOffset(buffer);
        int newRecordOffset = dataOffset - recordSize;

        // 2. 写入记录数据
        buffer.position(newRecordOffset);
        buffer.put(record);

        // 3. 移动槽目录
        int recordCount = getRecordCount(buffer);
        for (int i = recordCount; i > insertPos; i--) {
            int prevSlot = getSlot(buffer, i - 1);
            setSlot(buffer, i, prevSlot);
        }

        // 4. 写入新槽
        setSlot(buffer, insertPos, newRecordOffset);

        // 5. 更新头部
        setRecordCount(buffer, recordCount + 1);
        setDataOffset(buffer, newRecordOffset);

        return true;
    }
}
```

## 3. 删除操作

### 3.1 基本删除

```java
// 删除记录
boolean success = btree.delete(searchKey, recordSize, mtr);
```

### 3.2 删除算法

```
算法: B+Tree 删除
输入: searchKey, recordSize
输出: boolean (成功/失败)

1. path = searchPath(searchKey)
2. leafPage = path.getLeafPage()
3. recordIndex = findRecord(leafPage, searchKey)
4. if recordIndex < 0:
      return false  // 记录不存在
5. deleteFromPage(leafPage, recordIndex)
6. if leafPage.isUnderflow():
      // 需要合并或重分布
      handleUnderflow(path, leafPage)
7. return true
```

### 3.3 页面内删除

```java
public class PageDelete {
    public static boolean delete(ByteBuffer buffer, int deletePos) {
        int recordCount = getRecordCount(buffer);

        if (deletePos < 0 || deletePos >= recordCount) {
            return false;
        }

        // 移动槽目录（覆盖被删除的槽）
        for (int i = deletePos; i < recordCount - 1; i++) {
            int nextSlot = getSlot(buffer, i + 1);
            setSlot(buffer, i, nextSlot);
        }

        // 更新记录数
        setRecordCount(buffer, recordCount - 1);

        // 注意：记录空间不立即回收，等待页面压缩
        return true;
    }
}
```

## 4. 页面分裂

### 4.1 分裂时机

- 插入时页面空间不足
- 填充因子超过阈值

### 4.2 分裂算法

```
算法: 页面分裂
输入: fullPage, newRecord
输出: SplitResult

1. // 创建新页面
   newPage = allocatePage()
   newPage.level = fullPage.level

2. // 计算分裂点（中间位置）
   totalRecords = fullPage.recordCount + 1
   splitPoint = totalRecords / 2

3. // 移动后半部分记录到新页面
   for i = splitPoint to totalRecords:
      record = fullPage.getRecord(i)
      newPage.insert(record)
      fullPage.delete(i)

4. // 更新链表指针
   newPage.nextPage = fullPage.nextPage
   newPage.prevPage = fullPage.pageNo
   fullPage.nextPage = newPage.pageNo

5. // 返回分裂结果
   return SplitResult(
      splitKey = newPage.getFirstKey(),
      newPageNo = newPage.pageNo
   )
```

### 4.3 分裂传播

```
算法: 分裂传播
输入: path, splitResult

1. for level = 0 to path.height:
      parentPage = path.getPage(level + 1)

      if parentPage == null:
         // 需要创建新根
         createNewRoot(splitResult)
         break

      if parentPage.hasSpace():
         // 父节点有空间，插入分裂键
         insertIntoInternal(parentPage, splitResult)
         break
      else:
         // 父节点也需要分裂
         splitResult = splitInternalPage(parentPage, splitResult)
         // 继续向上传播
```

### 4.4 分裂示意图

```
分裂前:
┌─────────────────────────────────────┐
│ [10, 20, 30, 40, 50, 60, 70, 80]   │  (满)
└─────────────────────────────────────┘

分裂后:
┌─────────────────┐     ┌─────────────────┐
│ [10, 20, 30, 40]│ ←→  │ [50, 60, 70, 80]│
└─────────────────┘     └─────────────────┘
                              ↑
                         分裂键 = 50

父节点插入:
┌─────────────────────────────────────┐
│ [..., 50, ...]                      │
│       ↓                             │
│   指向新页面                         │
└─────────────────────────────────────┘
```

## 5. 页面合并

### 5.1 合并时机

- 删除后页面记录数低于阈值
- 填充因子低于 50%

### 5.2 合并算法

```
算法: 页面合并
输入: underflowPage, path

1. // 尝试从兄弟节点借记录
   leftSibling = getLeftSibling(underflowPage)
   rightSibling = getRightSibling(underflowPage)

2. if leftSibling != null && leftSibling.canLend():
      // 从左兄弟借
      borrowFromLeft(underflowPage, leftSibling)
      return

3. if rightSibling != null && rightSibling.canLend():
      // 从右兄弟借
      borrowFromRight(underflowPage, rightSibling)
      return

4. // 无法借，需要合并
   if leftSibling != null:
      mergePage(leftSibling, underflowPage)
      deleteSeparatorKey(path, underflowPage)
   else if rightSibling != null:
      mergePage(underflowPage, rightSibling)
      deleteSeparatorKey(path, rightSibling)
```

### 5.3 合并示意图

```
合并前:
┌─────────┐     ┌─────────┐     ┌─────────┐
│ [10,20] │ ←→  │ [30]    │ ←→  │ [40,50] │
└─────────┘     └─────────┘     └─────────┘
                    ↑
               下溢页面

合并后:
┌─────────────────┐     ┌─────────┐
│ [10, 20, 30]    │ ←→  │ [40,50] │
└─────────────────┘     └─────────┘
```

## 6. 范围扫描

### 6.1 全表扫描

```java
try (BTreeRangeScanner scanner = btree.fullScan(mtr)) {
    for (BTreeRangeScanner.ScanEntry entry : scanner) {
        byte[] key = entry.getKey();
        byte[] value = entry.getValue();
        // 处理记录
    }
}
```

### 6.2 范围扫描

```java
// 扫描 [10, 50) 范围
RangeBound lower = RangeBound.inclusive(IntKeyComparator.intToBytes(10));
RangeBound upper = RangeBound.exclusive(IntKeyComparator.intToBytes(50));

try (BTreeRangeScanner scanner = btree.rangeScan(mtr, lower, upper)) {
    for (BTreeRangeScanner.ScanEntry entry : scanner) {
        // 处理记录
    }
}
```

### 6.3 游标操作

```java
// 使用游标进行更细粒度的控制
BTreeCursor cursor = btree.openCursor(mtr);

// 定位到指定键
cursor.seek(searchKey);

// 向前遍历
while (cursor.hasNext()) {
    CursorPosition pos = cursor.next();
    byte[] key = pos.getKey();
    byte[] value = pos.getValue();
}

// 向后遍历
while (cursor.hasPrevious()) {
    CursorPosition pos = cursor.previous();
    // ...
}

cursor.close();
```

### 6.4 范围边界类型

```java
public class RangeBound {
    // 包含边界
    public static RangeBound inclusive(byte[] key);

    // 不包含边界
    public static RangeBound exclusive(byte[] key);

    // 无边界（负无穷/正无穷）
    public static RangeBound unbounded();
}
```

## 7. 路径追踪

### 7.1 BTreePath

```java
public class BTreePath {
    // 从根到叶子的页面路径
    private final List<PathEntry> entries;

    public static class PathEntry {
        private final PageId pageId;
        private final int childIndex;  // 在父节点中的位置
        private final int level;
    }

    // 获取叶子页面
    public PageId getLeafPageId();

    // 获取指定层级的页面
    public PathEntry getEntry(int level);

    // 获取父节点
    public PathEntry getParent(int level);
}
```

### 7.2 路径记录

```
搜索键 = 35

路径记录:
Level 2 (Root):    Page 1,  childIndex = 1
Level 1 (Internal): Page 5,  childIndex = 0
Level 0 (Leaf):    Page 12, recordIndex = 3

       ┌─────────────┐
       │  [30, 60]   │  Level 2, Page 1
       └──────┬──────┘
              │ childIndex=1
              ↓
       ┌─────────────┐
       │  [35, 45]   │  Level 1, Page 5
       └──────┬──────┘
              │ childIndex=0
              ↓
       ┌─────────────┐
       │[31,33,35,38]│  Level 0, Page 12
       └─────────────┘
              ↑
         recordIndex=2
```

## 8. 性能特征

| 操作 | 时间复杂度 | 说明 |
|------|-----------|------|
| 搜索 | O(log N) | N 为记录数 |
| 插入 | O(log N) | 可能触发分裂 |
| 删除 | O(log N) | 可能触发合并 |
| 范围扫描 | O(log N + M) | M 为结果数 |
| 全表扫描 | O(N) | 顺序扫描叶子链表 |

## 9. 最佳实践

### 9.1 批量插入

```java
// 对于大量插入，使用批量加载
BulkLoadConfig config = BulkLoadConfig.builder()
    .fillFactor(0.9)
    .sortInput(true)
    .build();

BTreeBulkLoader loader = new BTreeBulkLoader(bufferPool, comparator, config);
BulkLoadResult result = loader.bulkLoad(indexId, spaceId, records, mtr);
```

### 9.2 避免频繁分裂

```java
// 预留空间，减少分裂
// 使用较低的填充因子
BulkLoadConfig config = BulkLoadConfig.builder()
    .fillFactor(0.7)  // 70% 填充
    .build();
```

### 9.3 范围扫描优化

```java
// 使用前缀扫描
byte[] prefix = "user_".getBytes();
RangeBound lower = RangeBound.inclusive(prefix);
RangeBound upper = RangeBound.exclusive(incrementLastByte(prefix));

try (BTreeRangeScanner scanner = btree.rangeScan(mtr, lower, upper)) {
    // 只扫描以 "user_" 开头的键
}
```
