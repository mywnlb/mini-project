# 异常使用指南

## 概述

本文档说明如何在 MiniDB 项目中正确使用自定义异常体系。

## 异常层次结构

```
MiniDbException (基类)
├── StorageException (10xxxx)
│   ├── DiskIOException (1001xx)
│   └── PageCorruptedException (1002xx)
├── BufferException (20xxxx)
│   └── BufferExhaustedException (2001xx)
└── MtrException (30xxxx)
    ├── MtrStateException (3001xx)
    └── PageNotManagedByMtrException (3002xx)
```

## 错误码设计

格式：`MMTTSS`
- `MM`: 模块代码（10=Storage, 20=Buffer, 30=MTR）
- `TT`: 错误类型
- `SS`: 序号

### 已分配的错误码

| 错误码 | 异常类 | 说明 |
|--------|--------|------|
| 100101 | DiskIOException | 读取 EOF |
| 100102 | DiskIOException | 写入失败 |
| 100103 | DiskIOException | 文件打开失败 |
| 100201 | PageCorruptedException | 校验和不匹配 |
| 100202 | PageCorruptedException | 页面格式错误 |
| 200101 | BufferExhaustedException | 无空闲帧 |
| 200102 | BufferExhaustedException | 所有页面被固定 |
| 300101 | MtrStateException | MTR 已提交 |
| 300102 | MtrStateException | MTR 已中止 |
| 300201 | PageNotManagedByMtrException | 页面未在 memo 中 |

## 使用示例

### 1. DiskManager 中使用

**之前（不推荐）**:
```java
throw new IOException("Unexpected EOF reading page: " + pageNo);
```

**之后（推荐）**:
```java
// 方式 1: 使用静态工厂方法
throw DiskIOException.readEOF(pageNo);

// 方式 2: 直接构造
throw new DiskIOException(DiskIOException.ERR_READ_EOF,
    "Unexpected EOF reading page: " + pageNo);
```

### 2. BufferPool 中使用

**之前**:
```java
throw new IOException("Buffer pool exhausted: all pages are pinned");
```

**之后**:
```java
throw BufferExhaustedException.allPagesPinned(poolSize);
```

### 3. MiniTransaction 中使用

**之前**:
```java
throw new IllegalStateException("MTR is in " + state + " state");
```

**之后**:
```java
throw MtrStateException.notActive(state);
```

**之前**:
```java
throw new IllegalStateException("Page not managed by this MTR");
```

**之后**:
```java
throw PageNotManagedByMtrException.notInMemo(pageId);
```

### 4. Page 中使用

**校验和验证**:
```java
if (!verifyChecksum()) {
    throw PageCorruptedException.checksumMismatch(
        pageId, storedChecksum, calculatedChecksum);
}
```

**格式验证**:
```java
if (invalidFormat) {
    throw PageCorruptedException.invalidFormat(pageId, "Invalid slot directory");
}
```

## 异常捕获

### 按具体类型捕获

```java
try {
    Page page = mtr.getPage(pageId);
} catch (DiskIOException e) {
    if (e.getErrorCode() == DiskIOException.ERR_READ_EOF) {
        // 处理 EOF 错误
        log.error("Page not found: {}", pageId);
    } else {
        // 其他磁盘错误
        log.error("Disk I/O error: {}", e.getMessage());
    }
} catch (PageCorruptedException e) {
    // 页面损坏
    log.error("Corrupted page: {}", e.getMessage());
    // 可能需要修复或从备份恢复
}
```

### 按模块捕获

```java
try {
    mtr.commit();
} catch (MtrException e) {
    log.error("MTR error [{}]: {}", e.getErrorCode(), e.getMessage());
    // 处理 MTR 相关错误
} catch (BufferException e) {
    log.error("Buffer error [{}]: {}", e.getErrorCode(), e.getMessage());
    // 处理 Buffer Pool 错误
} catch (StorageException e) {
    log.error("Storage error [{}]: {}", e.getErrorCode(), e.getMessage());
    // 处理存储层错误
}
```

### 统一捕获

```java
try {
    // 数据库操作
} catch (MiniDbException e) {
    log.error("Database error [module={}, code={}]: {}",
        e.getModuleCode(), e.getErrorCode(), e.getMessage());

    // 根据模块代码分类处理
    switch (e.getModuleCode()) {
        case 10 -> handleStorageError((StorageException) e);
        case 20 -> handleBufferError((BufferException) e);
        case 30 -> handleMtrError((MtrException) e);
        default -> handleUnknownError(e);
    }
}
```

## 代码迁移检查清单

### DiskManager.java
- [ ] `IOException("Unexpected EOF")` → `DiskIOException.readEOF()`
- [ ] `IOException("Failed to write")` → `DiskIOException.writeFailed()`
- [ ] `IOException("Failed to open")` → `DiskIOException.fileOpenFailed()`

### BufferPool.java
- [ ] `IOException("Buffer pool exhausted")` → `BufferExhaustedException.allPagesPinned()`
- [ ] `IOException("No free frames")` → `BufferExhaustedException.noFreeFrames()`

### MiniTransaction.java
- [ ] `IllegalStateException("MTR is in...")` → `MtrStateException.notActive()`
- [ ] `IllegalStateException("Page not managed")` → `PageNotManagedByMtrException.notInMemo()`

### Page.java
- [ ] 添加校验和验证 → `PageCorruptedException.checksumMismatch()`
- [ ] 添加格式验证 → `PageCorruptedException.invalidFormat()`

## 最佳实践

### ✅ DO

1. **使用静态工厂方法**
   ```java
   throw DiskIOException.readEOF(pageNo);  // 清晰、简洁
   ```

2. **保留原始异常**
   ```java
   catch (IOException e) {
       throw DiskIOException.writeFailed(pageNo, e);  // 保留异常链
   }
   ```

3. **提供上下文信息**
   ```java
   throw PageCorruptedException.checksumMismatch(pageId, stored, calculated);
   ```

4. **记录错误码**
   ```java
   log.error("Error [{}]: {}", e.getErrorCode(), e.getMessage());
   ```

### ❌ DON'T

1. **不要吞掉异常**
   ```java
   catch (MiniDbException e) {
       // 空 catch 块 - 不要这样做！
   }
   ```

2. **不要丢失原始异常**
   ```java
   catch (IOException e) {
       throw new DiskIOException("Error");  // 丢失了 e！
   }
   ```

3. **不要使用通用异常**
   ```java
   throw new Exception("Something wrong");  // 太通用！
   ```

## 错误码查询

可以通过错误码快速定位问题：

```bash
# 查询错误码 100101
# 10 = Storage Layer
# 01 = Disk I/O
# 01 = EOF error
```

## 后续扩展

计划添加的异常类型：

### Transaction System (40xxxx)
- DeadlockException (400101)
- LockTimeoutException (400102)
- TransactionAbortedException (400103)

### SQL Layer (50xxxx)
- ParseException (500101)
- SemanticException (500201)
- ExecutionException (500301)

### Catalog (70xxxx)
- TableNotFoundException (700101)
- ColumnNotFoundException (700102)
- IndexNotFoundException (700103)

## 总结

使用自定义异常的优势：

1. **类型安全**: 编译时检查异常类型
2. **精确诊断**: 错误码和详细信息帮助快速定位问题
3. **分层处理**: 可以按模块或具体类型捕获异常
4. **可扩展**: 易于添加新的异常类型
5. **符合规范**: 遵循项目代码规范要求
