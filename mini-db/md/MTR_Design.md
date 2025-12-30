# Mini-Transaction (MTR) 设计文档

## 一、设计动机

### 问题背景

在没有 MTR 之前，直接使用 BufferPool 存在以下问题：

1. **资源管理复杂**：需要手动 pin/unpin 页面，容易忘记导致内存泄漏
2. **缺乏原子性**：多个页面的修改无法保证原子性
3. **错误处理困难**：出错时需要手动回滚所有已修改的页面
4. **代码冗余**：每次操作都要重复相同的 try-finally 模式

### MySQL InnoDB 的解决方案

InnoDB 使用 **Mini-Transaction (mtr_t)** 来解决这些问题：

- 自动管理页面的 pin/unpin
- 提供一组页面操作的原子性保证
- 自动生成 redo log 记录
- 简化错误处理和资源清理

## 二、MTR 核心设计

### 2.1 架构图

```
                  ┌─────────────────────────────┐
                  │   MiniTransaction (MTR)     │
                  ├─────────────────────────────┤
                  │ - state: State              │
                  │ - memo: List<MemoSlot>      │
                  │ - redoLogBuffer: List<byte[]>│
                  │ - bufferPool: BufferPool    │
                  ├─────────────────────────────┤
                  │ + getPage(pageId): Page     │
                  │ + newPage(spaceId): Page    │
                  │ + markDirty(page): void     │
                  │ + commit(): void            │
                  │ + rollback(): void          │
                  │ + close(): void             │
                  └──────────┬──────────────────┘
                             │
                ┌────────────┴────────────┐
                │                         │
                ▼                         ▼
    ┌──────────────────┐      ┌──────────────────┐
    │   BufferPool     │      │   Redo Log       │
    │                  │      │   (Future)       │
    │ - getPage()      │      │                  │
    │ - unpinPage()    │      │ - write()        │
    └──────────────────┘      └──────────────────┘
```

### 2.2 核心数据结构

#### MemoSlot - 页面记录

```java
class MemoSlot {
    PageId pageId;    // 页面标识
    Page page;        // 页面对象引用（缓存）
    boolean isDirty;  // 是否被修改
}
```

**作用**：
- 记录 MTR 获取的所有页面
- 追踪哪些页面被修改（需要刷盘）
- 按 LIFO 顺序释放（后获取先释放）

#### State - MTR 状态

```java
enum State {
    ACTIVE,     // 活跃状态，可以操作页面
    COMMITTED,  // 已提交，修改已持久化
    ABORTED     // 已中止，修改被丢弃
}
```

**状态转换图**：

```
    [NEW]
      │
      │ 构造函数
      ▼
   [ACTIVE] ───────────┐
      │                │
      │ commit()       │ rollback() / close()
      ▼                ▼
  [COMMITTED]      [ABORTED]
```

### 2.3 核心方法

#### getPage() - 获取页面

**执行流程**：

```
1. 检查 MTR 是否 ACTIVE
   ├─ YES: 继续
   └─ NO: 抛出 IllegalStateException

2. 检查 memo 中是否已有该页面
   ├─ YES: 直接返回缓存的 Page
   └─ NO: 从 BufferPool 获取

3. bufferPool.getPage(pageId, mode)
   └─ 自动 pin++

4. 加入 memo
   └─ new MemoSlot(pageId, page, isDirty=false)

5. 返回 Page 对象
```

**优点**：
- 自动去重：重复获取同一页面不会多次 pin
- 自动 pin：无需手动管理
- 缓存：避免重复查询 BufferPool

#### markDirty() - 标记脏页

**执行流程**：

```
1. 检查 MTR 是否 ACTIVE

2. 在 memo 中查找该页面
   ├─ 找到: slot.isDirty = true
   └─ 未找到: 抛出 IllegalStateException
             （必须先 getPage）

3. page.markDirty()
   └─ 设置 Page 的 dirty 标志
```

**为什么需要显式调用？**
- 明确语义：哪些页面被修改了
- 性能优化：避免所有页面都被当作脏页
- Redo Log：只为脏页生成 redo log

#### commit() - 提交

**执行流程**：

```
1. 检查状态（已提交则直接返回，幂等）

2. 生成 Redo Log
   └─ 遍历 redoLogBuffer
      └─ logManager.write(record)  [TODO]

3. 释放所有页面（LIFO 顺序）
   └─ for i = memo.size-1 down to 0:
        └─ bufferPool.unpinPage(pageId, isDirty)

4. 清理状态
   ├─ memo.clear()
   ├─ redoLogBuffer.clear()
   └─ state = COMMITTED
```

**为什么 LIFO 释放？**
- 遵循栈式内存管理
- 先释放子节点，后释放父节点（B+Tree 场景）
- 减少死锁可能性

#### rollback() - 回滚

**执行流程**：

```
1. 检查状态（已回滚则直接返回）

2. 丢弃 Redo Log
   └─ redoLogBuffer.clear()

3. 释放所有页面（LIFO，全部标记为非脏）
   └─ for i = memo.size-1 down to 0:
        └─ bufferPool.unpinPage(pageId, isDirty=false)

4. 清理状态
   ├─ memo.clear()
   └─ state = ABORTED
```

**注意**：
- 当前简化实现不会撤销已修改的页面内容
- 依赖页面未刷盘（在 Buffer Pool 中）
- 完整实现需要配合 Undo Log

#### close() - 自动清理

```java
@Override
public void close() {
    if (state == State.ACTIVE) {
        rollback();  // 未提交则自动回滚
    }
}
```

**配合 try-with-resources**：

```java
try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
    // 操作...
    // 如果忘记 commit，自动 rollback
}
```

## 三、使用模式

### 3.1 单页面操作

```java
try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
    Page page = mtr.getPage(pageId);

    page.putInt(offset, value);
    mtr.markDirty(page);

    mtr.commit();
}
```

### 3.2 多页面原子操作

```java
// 示例：B+Tree 插入需要修改叶子节点和父节点
try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
    Page leafPage = mtr.getPage(leafPageId);
    Page parentPage = mtr.getPage(parentPageId);

    // 插入到叶子节点
    leafPage.putBytes(offset, recordData);
    mtr.markDirty(leafPage);

    // 更新父节点统计
    parentPage.putInt(countOffset, newCount);
    mtr.markDirty(parentPage);

    // 原子提交：两个页面要么都成功，要么都失败
    mtr.commit();
}
```

### 3.3 错误处理

```java
try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
    Page page = mtr.getPage(pageId);

    if (!validate(page)) {
        // 显式回滚
        mtr.rollback();
        throw new ValidationException("Invalid page");
    }

    modify(page);
    mtr.markDirty(page);
    mtr.commit();

} catch (IOException e) {
    // 异常时自动回滚（try-with-resources）
    log.error("Operation failed: {}", e.getMessage());
}
```

### 3.4 批量操作

```java
// 批量插入：在一个 MTR 中完成
try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
    Page page = mtr.getPage(pageId);

    for (Record record : records) {
        page.putBytes(offset, record.getData());
        offset += record.getSize();
    }

    mtr.markDirty(page);
    mtr.commit();
}
```

## 四、与 BufferPool 的协作

### 4.1 Pin Count 管理

```
操作序列                    Pin Count
─────────────────────────────────────
mtr.getPage(page1)            1
mtr.getPage(page2)            1
mtr.getPage(page1)  # 重复   1 (不增加)

mtr.commit()
  └─ unpinPage(page1)         0
  └─ unpinPage(page2)         0
```

### 4.2 脏页传播

```
MTR                     BufferPool             FlushList
───────────────────────────────────────────────────────
markDirty(page1)      (仅内存标记)                -
markDirty(page2)      (仅内存标记)                -

commit()
  └─ unpinPage(page1, dirty=true)
                      frame.setDirty(true)
                      ──────────────────>      add(page1, lsn)
  └─ unpinPage(page2, dirty=true)
                      frame.setDirty(true)
                      ──────────────────>      add(page2, lsn)
```

## 五、性能考虑

### 5.1 MTR 粒度

**原则**：MTR 应该尽可能短小

```java
// ❌ 不好：MTR 持有时间过长
try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
    Page page = mtr.getPage(pageId);

    // 耗时操作（如网络 I/O、复杂计算）
    expensiveOperation();  // 不应该在 MTR 中

    mtr.commit();
}

// ✅ 好：MTR 只包含页面操作
expensiveOperation();  // 先完成耗时操作

try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
    Page page = mtr.getPage(pageId);
    page.putInt(offset, result);
    mtr.markDirty(page);
    mtr.commit();
}
```

### 5.2 页面缓存

MTR 内部缓存已获取的页面：

```java
try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
    Page page1 = mtr.getPage(pageId);  // 从 BufferPool 获取
    Page page2 = mtr.getPage(pageId);  // 从 memo 缓存返回

    assertSame(page1, page2);  // 同一个对象
}
```

### 5.3 Memo 大小

- **一般场景**：1-10 个页面
- **B+Tree 分裂**：3-5 个页面
- **批量操作**：应该分批，避免单个 MTR 持有过多页面

## 六、未来扩展

### 6.1 完整 Redo Log 支持

```java
public void commit() throws IOException {
    // 生成 redo log records
    for (MemoSlot slot : memo) {
        if (slot.isDirty) {
            RedoLogRecord record = generateRedoLog(slot.page);
            redoLogBuffer.add(record);
        }
    }

    // 写入 Redo Log（WAL 规则）
    long lsn = logManager.write(redoLogBuffer);

    // 更新页面 LSN
    for (MemoSlot slot : memo) {
        if (slot.isDirty) {
            slot.page.setLsn(lsn);
        }
    }

    // 释放页面
    // ...
}
```

### 6.2 锁管理集成

```java
// 获取页面时自动加锁
public Page getPageWithLock(PageId pageId, LockMode mode) {
    Page page = getPage(pageId);

    // 记录锁信息到 memo
    lockManager.lock(pageId, mode);

    return page;
}

// commit 时自动释放锁
public void commit() {
    // ...

    // 释放所有锁
    for (MemoSlot slot : memo) {
        lockManager.unlock(slot.pageId);
    }
}
```

### 6.3 嵌套 MTR

```java
try (MiniTransaction outerMtr = new MiniTransaction(bufferPool)) {
    Page page1 = outerMtr.getPage(pageId1);

    // 嵌套 MTR（子操作）
    try (MiniTransaction innerMtr = outerMtr.createSubMtr()) {
        Page page2 = innerMtr.getPage(pageId2);
        innerMtr.commit();
    }

    outerMtr.commit();
}
```

## 七、测试覆盖

已实现的测试用例：

1. ✅ 基本页面获取和释放
2. ✅ 脏页标记
3. ✅ 多页面管理
4. ✅ 重复获取同一页面
5. ✅ 显式 commit
6. ✅ 显式 rollback
7. ✅ 自动 rollback (try-with-resources)
8. ✅ 重复 commit（幂等性）
9. ✅ COMMITTED 状态下操作（异常）
10. ✅ 标记未管理页面为脏（异常）
11. ✅ 统计信息
12. ✅ hasPage 和 isDirty 查询
13. ✅ Pin Count 管理

## 八、与 InnoDB 的对比

| 特性 | InnoDB | MiniDB | 说明 |
|------|--------|--------|------|
| 页面管理 | mtr_memo | memo (List<MemoSlot>) | 相同 |
| 状态机 | implicit | explicit (State enum) | MiniDB 更明确 |
| Redo Log | 完整实现 | 占位（TODO） | 待实现 |
| 锁管理 | MTR_MEMO_X_LOCK / S_LOCK | 无 | 待实现 |
| 嵌套 MTR | 支持 | 不支持 | 待实现 |
| 自动清理 | 手动 mtr_commit | AutoCloseable | MiniDB 更便利 |
| Log Buffer | mtr_t.log | redoLogBuffer | 类似 |

## 九、最佳实践

### ✅ DO

1. **始终使用 try-with-resources**
   ```java
   try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
       // ...
   }
   ```

2. **修改后立即 markDirty**
   ```java
   page.putInt(offset, value);
   mtr.markDirty(page);  // 紧跟修改
   ```

3. **显式 commit**
   ```java
   mtr.commit();  // 明确提交
   ```

4. **批量操作在一个 MTR 中**
   ```java
   try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
       for (Record r : records) {
           // ...
       }
       mtr.commit();
   }
   ```

### ❌ DON'T

1. **跨 MTR 使用 Page 对象**
   ```java
   Page page;
   try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
       page = mtr.getPage(pageId);
       mtr.commit();
   }
   page.putInt(0, 123);  // ❌ 危险！MTR 已结束
   ```

2. **忘记 markDirty**
   ```java
   page.putInt(offset, value);
   // mtr.markDirty(page);  // ❌ 忘记标记，修改不会持久化
   mtr.commit();
   ```

3. **MTR 持有时间过长**
   ```java
   try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
       Page page = mtr.getPage(pageId);
       Thread.sleep(10000);  // ❌ 不要阻塞 MTR
       mtr.commit();
   }
   ```

4. **跨线程共享 MTR**
   ```java
   MiniTransaction mtr = new MiniTransaction(bufferPool);
   executor.submit(() -> {
       mtr.getPage(pageId);  // ❌ MTR 不是线程安全的
   });
   ```

## 十、总结

MTR 是数据库存储引擎中的关键抽象，它：

1. **简化编程模型**：自动管理资源，减少错误
2. **提供原子性**：一组页面操作的 ACID 保证
3. **性能优化**：批量操作、缓存、减少重复获取
4. **易于扩展**：为 Redo Log、锁管理等高级特性奠定基础

通过 MTR，我们将 BufferPool 的复杂性封装起来，为上层模块（B+Tree、Handler、Transaction）提供了简洁统一的页面访问接口。
