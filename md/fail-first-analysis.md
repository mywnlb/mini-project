# Fail-First 设计分析

## 概述

本文档按照 CLAUDE.md 的 Fail-First 设计门控要求，分析三个核心功能的禁止设计和不变量。

---

## 1. appendMergedUndo() - Fail-First 分析

### 功能描述
在 UndoLogManager 中实现，用于追加写合并后的 Undo 记录。

### 禁止设计 1：直接修改旧 Undo 记录

**设计描述**：
```java
// ❌ 禁止：直接修改旧 Undo 记录
public void appendMergedUndo(UpdateUndoRecord mergedUndo) {
    // 找到旧的 Undo 记录
    UpdateUndoRecord oldUndo = findOldUndo(mergedUndo.getPrevUndoPtr());

    // 直接修改旧 Undo 的 prev_ptr 指向新的合并 Undo
    oldUndo.setPrevUndoPtr(newMergedPtr);  // ❌ 直接修改！
}
```

**为什么危险**：
- 违反 **U1 不变量**：Undo 记录不可修改
- 旧 Undo 记录已经被写入磁盘
- 修改会导致磁盘上的数据与内存不一致

**哪个模块会破坏**：
- 版本重建模块：读取旧 Undo 时获得错误的 prev_ptr
- 恢复管理器：恢复时重放错误的 Undo 链
- MVCC 模块：版本链指针错误

**失败类型**：
- 🔴 **无声损坏**：系统不会立即崩溃，但版本链被破坏
- 后续查询返回错误的历史版本
- 恢复时可能无法正确重建版本链

**验证方法**：
```java
// 验证：旧 Undo 记录不应该被修改
@Test
void testOldUndoNotModified() {
    UpdateUndoRecord oldUndo = readUndoFromDisk(oldUndoPtr);
    byte[] originalData = oldUndo.serialize();

    // 执行 appendMergedUndo
    appendMergedUndo(mergedUndo);

    // 验证旧 Undo 未被修改
    byte[] afterData = readUndoFromDisk(oldUndoPtr).serialize();
    assertEquals(originalData, afterData);
}
```

---

### 禁止设计 2：不生成 redo 日志

**设计描述**：
```java
// ❌ 禁止：不生成 redo 日志
public RollbackPointer appendMergedUndo(UpdateUndoRecord mergedUndo) {
    // 直接写入 Undo Page，不生成 redo
    UndoPage page = getUndoPage();
    int offset = page.writeUndoRecord(mergedUndo);  // ❌ 无 redo！

    return RollbackPointer.forInsert(rsegId, pageNo, offset);
}
```

**为什么危险**：
- 违反 **MTR 绝对规则**：所有页面修改必须生成 redo
- 如果系统在写入后崩溃，新 Undo 记录会丢失
- 恢复时无法重建合并后的 Undo 链

**哪个模块会破坏**：
- 恢复管理器：无法恢复新 Undo 记录
- 事务管理器：无法正确回滚
- Purge 线程：无法清理旧 Undo

**失败类型**：
- 🔴 **崩溃失败**：系统崩溃后无法恢复
- 数据丢失
- 版本链不完整

**验证方法**：
```java
// 验证：必须生成 redo 日志
@Test
void testRedoLogGenerated() {
    RedoLogBuffer redoBuffer = new RedoLogBuffer();

    try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
        RollbackPointer ptr = appendMergedUndo(mergedUndo, mtr);

        // 验证 redo 日志已生成
        assertTrue(redoBuffer.hasRedoRecord(ptr));
    }
}
```

---

### 禁止设计 3：不验证 RollbackPointer 有效性

**设计描述**：
```java
// ❌ 禁止：不验证 RollbackPointer 有效性
public RollbackPointer appendMergedUndo(UpdateUndoRecord mergedUndo) {
    // 直接使用 prevUndoPtr，不验证
    RollbackPointer prevPtr = mergedUndo.getPrevUndoPtr();

    // 如果 prevPtr 无效，后续版本重建会失败
    return RollbackPointer.forInsert(rsegId, pageNo, offset);
}
```

**为什么危险**：
- 违反 **U2 不变量**：版本链完整性
- 如果 prevUndoPtr 指向已清理的 Undo，版本链断裂
- 版本重建时无法找到完整的历史版本

**哪个模块会破坏**：
- 版本重建模块：无法遍历完整的版本链
- MVCC 模块：无法获取正确的历史版本
- 查询模块：返回不完整的版本

**失败类型**：
- 🔴 **无声损坏**：版本链断裂，查询返回错误结果

**验证方法**：
```java
// 验证：prevUndoPtr 必须有效
@Test
void testPrevUndoPtrValid() {
    // prevUndoPtr 应该指向有效的 Undo 记录
    RollbackPointer prevPtr = mergedUndo.getPrevUndoPtr();

    if (!prevPtr.isNull()) {
        UpdateUndoRecord prevUndo = readUndo(prevPtr);
        assertNotNull(prevUndo);
    }
}
```

---

### 核心不变量

#### U1：Undo 记录不可修改
- **定义**：一旦 Undo 记录被写入磁盘，不能修改其内容
- **实现**：只能追加写新 Undo 记录，不能修改旧记录
- **验证**：旧 Undo 记录的序列化形式不变

#### U2：版本链完整性
- **定义**：每个 Undo 记录的 prev_ptr 必须指向有效的 Undo 或 NULL
- **实现**：合并后的 Undo 必须指向链段之前的有效 Undo
- **验证**：版本链可以完整遍历

#### U3：Redo 日志保证
- **定义**：所有 Undo 页面修改必须生成 redo 日志
- **实现**：在 MTR 保护下写入 Undo，生成 redo
- **验证**：redo 日志包含新 Undo 记录

---

## 2. updateRollPtr() - Fail-First 分析

### 功能描述
在 ClusteredIndexAccessor 中实现，用于更新记录的 roll_ptr 指向新的合并 Undo。

### 禁止设计 1：不持有 X-latch

**设计描述**：
```java
// ❌ 禁止：不持有 X-latch
public boolean updateRollPtr(Record record, RollbackPointer newRollPtr) {
    // 直接修改 roll_ptr，不持有 latch
    record.setRollPtr(newRollPtr);  // ❌ 无 latch！
    return true;
}
```

**为什么危险**：
- 违反 **并发安全不变量**：数据页修改必须持有 X-latch
- 其他线程可能同时修改同一记录
- 导致 roll_ptr 被覆盖或损坏

**哪个模块会破坏**：
- MVCC 模块：版本链指针错误
- 并发控制模块：脏读、幻读
- 版本重建模块：无法找到正确的 Undo

**失败类型**：
- 🔴 **无声损坏**：并发修改导致 roll_ptr 错误
- 版本链指针丢失
- 查询返回错误的版本

**验证方法**：
```java
// 验证：必须持有 X-latch
@Test
void testXLatchRequired() {
    Page page = bufferPool.getPage(record.getPageId());

    // 不持有 latch 时应该失败
    assertThrows(LatchException.class, () -> {
        updateRollPtr(record, newRollPtr);
    });

    // 持有 X-latch 时应该成功
    page.xLatch();
    try {
        assertTrue(updateRollPtr(record, newRollPtr));
    } finally {
        page.xUnlatch();
    }
}
```

---

### 禁止设计 2：不验证 ABA 冲突

**设计描述**：
```java
// ❌ 禁止：不验证 ABA 冲突
public boolean updateRollPtr(Record record, RollbackPointer newRollPtr) {
    // 直接更新，不检查 roll_ptr 是否被修改
    RollbackPointer oldRollPtr = record.getRollPtr();
    record.setRollPtr(newRollPtr);  // ❌ 无 ABA 检查！
    return true;
}
```

**为什么危险**：
- 违反 **ABA 冲突检测不变量**
- 在识别可压缩链段和更新 roll_ptr 之间，记录可能被修改
- 导致压缩错误的 Undo 链

**哪个模块会破坏**：
- 压缩管理器：压缩错误的链段
- 版本重建模块：版本链不完整
- MVCC 模块：版本链指针错误

**失败类型**：
- 🔴 **无声损坏**：压缩错误的链段，版本链被破坏

**验证方法**：
```java
// 验证：必须检测 ABA 冲突
@Test
void testABAConflictDetection() {
    RollbackPointer originalRollPtr = record.getRollPtr();

    // 模拟 ABA 冲突：roll_ptr 被修改后又改回
    record.setRollPtr(RollbackPointer.forInsert(0, 10, 100));
    record.setRollPtr(originalRollPtr);

    // 应该检测到冲突
    assertFalse(updateRollPtr(record, newRollPtr, originalRollPtr));
}
```

---

### 禁止设计 3：不生成 redo 日志

**设计描述**：
```java
// ❌ 禁止：不生成 redo 日志
public boolean updateRollPtr(Record record, RollbackPointer newRollPtr) {
    page.xLatch();
    try {
        record.setRollPtr(newRollPtr);  // ❌ 无 redo！
        return true;
    } finally {
        page.xUnlatch();
    }
}
```

**为什么危险**：
- 违反 **MTR 绝对规则**：所有页面修改必须生成 redo
- 系统崩溃后，roll_ptr 更新会丢失
- 恢复时版本链指针错误

**哪个模块会破坏**：
- 恢复管理器：无法恢复 roll_ptr 更新
- 版本重建模块：版本链指针错误
- MVCC 模块：版本链不完整

**失败类型**：
- 🔴 **崩溃失败**：系统崩溃后无法恢复

**验证方法**：
```java
// 验证：必须生成 redo 日志
@Test
void testRedoLogGenerated() {
    try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
        page.xLatch();
        try {
            updateRollPtr(record, newRollPtr, mtr);

            // 验证 redo 日志已生成
            assertTrue(mtr.hasRedoRecord());
        } finally {
            page.xUnlatch();
        }
        mtr.commit();
    }
}
```

---

### 核心不变量

#### C1：X-latch 保护
- **定义**：所有数据页修改必须持有 X-latch
- **实现**：在修改前获取 X-latch，修改后释放
- **验证**：无 latch 时修改应该失败

#### C2：ABA 冲突检测
- **定义**：更新前必须验证 roll_ptr 未被修改
- **实现**：比较更新前后的 roll_ptr
- **验证**：ABA 冲突时更新失败

#### C3：Redo 日志保证
- **定义**：所有数据页修改必须生成 redo 日志
- **实现**：在 MTR 保护下修改，生成 redo
- **验证**：redo 日志包含 roll_ptr 更新

---

## 3. identifyCompressionCandidates() - Fail-First 分析

### 功能描述
在 CompressionThread 中实现，用于识别待压缩的 Undo 链段。

### 禁止设计 1：不检查 trx_id < purge_limit

**设计描述**：
```java
// ❌ 禁止：不检查 trx_id < purge_limit
private List<CompressionCandidate> identifyCompressionCandidates(
    TransactionId purgeLimit) {
    List<CompressionCandidate> candidates = new ArrayList<>();

    // 扫描所有记录，不检查 trx_id
    for (Record record : scanAllRecords()) {
        List<UpdateUndoRecord> undoChain = getUndoChain(record);

        // 直接加入候选，不验证 trx_id
        if (undoChain.size() >= MIN_CHAIN_LENGTH) {
            candidates.add(new CompressionCandidate(record, undoChain));  // ❌ 无检查！
        }
    }

    return candidates;
}
```

**为什么危险**：
- 违反 **P1 不变量**：不能清理活跃 ReadView 需要的 Undo
- 可能压缩活跃事务的 Undo 链
- 导致 ReadView 无法获取正确的历史版本

**哪个模块会破坏**：
- MVCC 模块：ReadView 无法获取历史版本
- 并发控制模块：隔离级别被破坏
- 查询模块：返回错误的版本

**失败类型**：
- 🔴 **无声损坏**：压缩活跃事务的 Undo，导致查询返回错误版本
- 违反隔离级别保证

**验证方法**：
```java
// 验证：只能压缩 trx_id < purge_limit 的链
@Test
void testOnlyCompressOldTransactions() {
    TransactionId purgeLimit = new TransactionId(100);

    // 创建 trx_id >= purge_limit 的 Undo
    UpdateUndoRecord activeUndo = new UpdateUndoRecord(
        new TransactionId(150),  // >= purge_limit
        tableId, prevPtr, pk, cols
    );

    List<CompressionCandidate> candidates = identifyCompressionCandidates(purgeLimit);

    // 不应该包含活跃事务的 Undo
    for (CompressionCandidate candidate : candidates) {
        for (UpdateUndoRecord undo : candidate.undoChain) {
            assertTrue(undo.getTrxId().getValue() < purgeLimit.getValue());
        }
    }
}
```

---

### 禁止设计 2：不检查链长度

**设计描述**：
```java
// ❌ 禁止：不检查链长度
private List<CompressionCandidate> identifyCompressionCandidates(
    TransactionId purgeLimit) {
    List<CompressionCandidate> candidates = new ArrayList<>();

    for (Record record : scanAllRecords()) {
        List<UpdateUndoRecord> undoChain = getUndoChain(record);

        // 即使链长度为 1 也加入候选
        if (undoChain.size() >= 1) {  // ❌ 应该 >= 2！
            candidates.add(new CompressionCandidate(record, undoChain));
        }
    }

    return candidates;
}
```

**为什么危险**：
- 违反 **压缩效率不变量**：只有长度 >= 2 的链才值得压缩
- 压缩单个 Undo 浪费 CPU 和 I/O
- 导致性能下降

**哪个模块会破坏**：
- 性能管理模块：压缩吞吐量下降
- 资源管理模块：CPU 浪费

**失败类型**：
- 🟡 **性能下降**：压缩效率低

**验证方法**：
```java
// 验证：只压缩长度 >= 2 的链
@Test
void testMinimumChainLength() {
    TransactionId purgeLimit = new TransactionId(100);

    // 创建长度为 1 的链
    UpdateUndoRecord singleUndo = new UpdateUndoRecord(...);
    List<UpdateUndoRecord> shortChain = Arrays.asList(singleUndo);

    List<CompressionCandidate> candidates = identifyCompressionCandidates(purgeLimit);

    // 不应该包含长度 < 2 的链
    for (CompressionCandidate candidate : candidates) {
        assertTrue(candidate.undoChain.size() >= 2);
    }
}
```

---

### 禁止设计 3：不检查合并后大小

**设计描述**：
```java
// ❌ 禁止：不检查合并后大小
private List<CompressionCandidate> identifyCompressionCandidates(
    TransactionId purgeLimit) {
    List<CompressionCandidate> candidates = new ArrayList<>();

    for (Record record : scanAllRecords()) {
        List<UpdateUndoRecord> undoChain = getUndoChain(record);

        if (undoChain.size() >= MIN_CHAIN_LENGTH) {
            // 不检查合并后大小
            candidates.add(new CompressionCandidate(record, undoChain));  // ❌ 无大小检查！
        }
    }

    return candidates;
}
```

**为什么危险**：
- 违反 **Undo 大小限制不变量**：合并后 Undo 不能超过 64KB
- 可能创建过大的 Undo 记录
- 导致 Undo Page 空间不足

**哪个模块会破坏**：
- Undo 空间管理模块：页面空间不足
- 压缩管理器：无法写入合并后的 Undo

**失败类型**：
- 🔴 **无声损坏**：合并后 Undo 无法写入，版本链不完整

**验证方法**：
```java
// 验证：合并后大小不超过限制
@Test
void testMergedUndoSizeLimit() {
    TransactionId purgeLimit = new TransactionId(100);

    // 创建合并后会超过 64KB 的链
    List<UpdateUndoRecord> largeChain = createLargeChain(100 * 1024);

    List<CompressionCandidate> candidates = identifyCompressionCandidates(purgeLimit);

    // 不应该包含合并后超过限制的链
    for (CompressionCandidate candidate : candidates) {
        int mergedSize = estimateMergedSize(candidate.undoChain);
        assertTrue(mergedSize <= MAX_MERGED_UNDO_SIZE);
    }
}
```

---

### 核心不变量

#### P1：Purge 边界保护
- **定义**：只能压缩 trx_id < purge_limit 的 Undo 链
- **实现**：检查链中所有 Undo 的 trx_id
- **验证**：所有候选链的 trx_id 都 < purge_limit

#### P2：最小链长度
- **定义**：只压缩长度 >= 2 的链
- **实现**：检查链长度
- **验证**：所有候选链长度 >= 2

#### P3：合并后大小限制
- **定义**：合并后 Undo 不能超过 64KB
- **实现**：估算合并后大小，超过限制则跳过
- **验证**：所有候选的合并后大小 <= 64KB

---

## 总结

### 禁止设计总览

| 功能 | 禁止设计 | 危险 | 失败类型 |
|------|---------|------|---------|
| appendMergedUndo | 直接修改旧 Undo | 违反 U1 | 无声损坏 |
| appendMergedUndo | 不生成 redo | 违反 MTR | 崩溃失败 |
| appendMergedUndo | 不验证 RollPtr | 违反 U2 | 无声损坏 |
| updateRollPtr | 不持有 X-latch | 违反并发安全 | 无声损坏 |
| updateRollPtr | 不验证 ABA | 违反 ABA 检测 | 无声损坏 |
| updateRollPtr | 不生成 redo | 违反 MTR | 崩溃失败 |
| identifyCompressionCandidates | 不检查 trx_id | 违反 P1 | 无声损坏 |
| identifyCompressionCandidates | 不检查链长度 | 违反效率 | 性能下降 |
| identifyCompressionCandidates | 不检查大小 | 违反大小限制 | 无声损坏 |

### 核心不变量总览

| 不变量 | 定义 | 验证方法 |
|--------|------|---------|
| U1 | Undo 记录不可修改 | 旧 Undo 序列化形式不变 |
| U2 | 版本链完整性 | 版本链可完整遍历 |
| U3 | Redo 日志保证 | redo 日志包含修改 |
| C1 | X-latch 保护 | 无 latch 时修改失败 |
| C2 | ABA 冲突检测 | ABA 冲突时更新失败 |
| C3 | Redo 日志保证 | redo 日志包含更新 |
| P1 | Purge 边界保护 | 只压缩 trx_id < purge_limit |
| P2 | 最小链长度 | 只压缩长度 >= 2 |
| P3 | 合并后大小限制 | 合并后大小 <= 64KB |

---

## 下一步

这个 Fail-First 分析文档定义了：

1. ✅ **9 个禁止设计**（为什么不能这样做）
2. ✅ **9 个核心不变量**（什么是正确的）
3. ✅ **验证方法**（如何验证正确性）

只有通过这个 Fail-First Gate，才能继续实现这三个功能。

