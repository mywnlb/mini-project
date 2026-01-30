# WAL (Write-Ahead Logging) 实现

## 1. 概述

MiniDB 实现了基于 ARIES (Algorithms for Recovery and Isolation Exploiting Semantics) 风格的 WAL 系统，提供：
- 事务原子性保证
- 崩溃恢复能力
- 持久性保证

## 2. 核心组件

### 2.1 组件架构

```
┌─────────────────────────────────────────────────────────────┐
│                      应用层                                  │
│                    WalBTree                                 │
├─────────────────────────────────────────────────────────────┤
│                    WAL 管理层                                │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │  WalManager  │  │RecoveryMgr   │  │  LogReader   │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
├─────────────────────────────────────────────────────────────┤
│                    日志缓冲层                                │
│  ┌──────────────────────────────────────────────────┐      │
│  │                   LogBuffer                       │      │
│  │  [缓冲区 64KB] → [日志文件 ≤16MB] → [轮转]        │      │
│  └──────────────────────────────────────────────────┘      │
├─────────────────────────────────────────────────────────────┤
│                    日志记录层                                │
│  ┌──────────────┐  ┌──────────────┐                        │
│  │  LogRecord   │  │LogRecordType │                        │
│  └──────────────┘  └──────────────┘                        │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 类职责

| 类名 | 职责 |
|------|------|
| `LogRecordType` | 日志记录类型枚举 |
| `LogRecord` | 日志记录数据结构和序列化 |
| `LogBuffer` | 日志缓冲区和文件管理 |
| `WalManager` | 事务和日志写入管理 |
| `LogReader` | 日志读取和遍历 |
| `RecoveryManager` | 崩溃恢复 (ARIES 三阶段) |
| `WalBTree` | B+Tree 与 WAL 集成 |

## 3. 日志记录类型

### 3.1 LogRecordType 枚举

```java
public enum LogRecordType {
    // 事务控制
    BEGIN(1),           // 事务开始
    COMMIT(2),          // 事务提交
    ABORT(3),           // 事务回滚

    // 数据操作 (需要 Redo 和 Undo)
    INSERT(10),         // 插入记录
    DELETE(11),         // 删除记录
    UPDATE(12),         // 更新记录

    // 页面操作 (仅需要 Redo)
    PAGE_INIT(20),      // 页面初始化
    PAGE_SPLIT(21),     // 页面分裂
    SET_PAGE_LINK(22),  // 设置页面链接

    // 系统操作
    CHECKPOINT(30),     // 检查点
    CLR(31),            // 补偿日志记录 (Undo 的 Redo)
    END(32);            // 事务结束
}
```

### 3.2 操作分类

| 类型 | Redo | Undo | 说明 |
|------|------|------|------|
| BEGIN/COMMIT/ABORT | ✗ | ✗ | 事务控制，无数据操作 |
| INSERT/DELETE/UPDATE | ✓ | ✓ | 数据操作，需要双向恢复 |
| PAGE_INIT/SPLIT/LINK | ✓ | ✗ | 页面结构操作，仅前向恢复 |
| CHECKPOINT | ✗ | ✗ | 检查点标记 |
| CLR | ✓ | ✗ | 补偿记录，防止重复 Undo |

## 4. 日志记录格式

### 4.1 LogRecord 结构

```
┌────────────────────────────────────────────────────┐
│                 LogRecord Header                    │
├──────────────┬─────────────────────────────────────┤
│ LSN          │ 8 bytes - 日志序列号                 │
├──────────────┼─────────────────────────────────────┤
│ Type         │ 1 byte  - 记录类型                   │
├──────────────┼─────────────────────────────────────┤
│ TxnId        │ 8 bytes - 事务 ID                    │
├──────────────┼─────────────────────────────────────┤
│ PrevLsn      │ 8 bytes - 同事务上一条日志 LSN       │
├──────────────┼─────────────────────────────────────┤
│ SpaceId      │ 4 bytes - 表空间 ID                  │
├──────────────┼─────────────────────────────────────┤
│ PageNo       │ 4 bytes - 页号                       │
├──────────────┼─────────────────────────────────────┤
│ PayloadLen   │ 4 bytes - 负载长度                   │
├──────────────┼─────────────────────────────────────┤
│ Payload      │ N bytes - 操作数据                   │
├──────────────┼─────────────────────────────────────┤
│ CRC32        │ 4 bytes - 校验和                     │
└──────────────┴─────────────────────────────────────┘
```

### 4.2 Payload 格式

**INSERT/DELETE:**
```
┌────────────┬────────────┬────────────┐
│ SlotNo (4) │ RecLen (4) │ RecData(N) │
└────────────┴────────────┴────────────┘
```

**UPDATE:**
```
┌────────────┬────────────┬────────────┬────────────┐
│ SlotNo (4) │ OldLen (4) │ OldData(N) │ NewLen (4) │ NewData(N) │
└────────────┴────────────┴────────────┴────────────┘
```

**SET_PAGE_LINK:**
```
┌────────────┬────────────┐
│ PrevPage(4)│ NextPage(4)│
└────────────┴────────────┘
```

## 5. LogBuffer 实现

### 5.1 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| 缓冲区大小 | 64KB | 内存中的日志缓冲 |
| 最大文件大小 | 16MB | 单个日志文件上限 |
| 文件前缀 | "wal" | 日志文件命名前缀 |

### 5.2 核心操作

```java
public class LogBuffer implements AutoCloseable {

    // 追加日志记录
    public long append(LogRecord record) throws IOException {
        lock.lock();
        try {
            byte[] data = record.serialize();

            // 检查是否需要刷盘
            if (buffer.remaining() < data.length) {
                flush();
            }

            // 检查是否需要轮转
            if (currentFileSize + data.length > MAX_LOG_FILE_SIZE) {
                rotateLogFile();
            }

            // 写入缓冲区
            long lsn = nextLsn.getAndAdd(data.length);
            buffer.put(data);
            return lsn;
        } finally {
            lock.unlock();
        }
    }

    // 刷盘
    public void flush() throws IOException {
        if (buffer.position() > 0) {
            buffer.flip();
            channel.write(buffer);
            channel.force(false);
            buffer.clear();
        }
    }

    // 日志文件轮转
    private void rotateLogFile() throws IOException {
        flush();
        closeCurrentFile();
        currentFileNumber++;
        openNewFile();
    }
}
```

### 5.3 LSN 管理

- **LSN (Log Sequence Number)**: 全局单调递增
- **格式**: 64 位整数，表示日志的字节偏移
- **生成**: `nextLsn.getAndAdd(recordLength)`

## 6. WalManager 实现

### 6.1 事务管理

```java
public class WalManager implements AutoCloseable {

    // 活跃事务表: txnId -> lastLsn
    private final Map<Long, Long> activeTxns;

    // 开始事务
    public long beginTransaction() throws IOException {
        long txnId = txnIdGenerator.incrementAndGet();
        LogRecord beginRecord = LogRecord.beginRecord(txnId);
        long lsn = logBuffer.append(beginRecord);
        activeTxns.put(txnId, lsn);
        return txnId;
    }

    // 提交事务
    public void commitTransaction(long txnId) throws IOException {
        Long prevLsn = activeTxns.get(txnId);
        LogRecord commitRecord = LogRecord.commitRecord(txnId, prevLsn);
        logBuffer.append(commitRecord);
        logBuffer.flush();  // 提交时必须刷盘
        activeTxns.remove(txnId);
    }

    // 回滚事务
    public void rollbackTransaction(long txnId) throws IOException {
        Long prevLsn = activeTxns.get(txnId);
        LogRecord abortRecord = LogRecord.abortRecord(txnId, prevLsn);
        logBuffer.append(abortRecord);
        activeTxns.remove(txnId);
    }
}
```

### 6.2 日志写入接口

```java
// 记录插入操作
public long logInsert(long txnId, int spaceId, int pageNo,
                      int slotNo, byte[] recordData) throws IOException {
    Long prevLsn = activeTxns.get(txnId);
    LogRecord record = LogRecord.insertRecord(txnId, prevLsn,
                                              spaceId, pageNo, slotNo, recordData);
    long lsn = logBuffer.append(record);
    activeTxns.put(txnId, lsn);
    return lsn;
}

// 记录删除操作
public long logDelete(long txnId, int spaceId, int pageNo,
                      int slotNo, byte[] recordData) throws IOException {
    // 类似 logInsert
}

// 记录页面分裂
public long logPageSplit(long txnId, int spaceId, int pageNo,
                        byte[] splitKey, int newPageNo) throws IOException {
    // ...
}

// 执行检查点
public void checkpoint() throws IOException {
    logBuffer.flush();
    LogRecord cpRecord = LogRecord.checkpointRecord(
        new ArrayList<>(activeTxns.keySet()),
        dirtyPages
    );
    logBuffer.append(cpRecord);
    logBuffer.flush();
}
```

## 7. LogReader 实现

### 7.1 顺序读取

```java
public class LogReader implements Iterator<LogRecord>, AutoCloseable {

    private final Path logDir;
    private RandomAccessFile currentFile;
    private long currentLsn;

    // 从指定 LSN 开始读取
    public LogReader(Path logDir, long startLsn) throws IOException {
        this.logDir = logDir;
        this.currentLsn = startLsn;
        locateFile(startLsn);
    }

    @Override
    public boolean hasNext() {
        return peekNext() != null;
    }

    @Override
    public LogRecord next() {
        LogRecord record = readNextRecord();
        currentLsn += record.getSerializedLength();
        return record;
    }

    private LogRecord readNextRecord() throws IOException {
        // 读取固定头部
        byte[] header = new byte[HEADER_SIZE];
        currentFile.readFully(header);

        // 解析负载长度
        int payloadLen = parsePayloadLength(header);

        // 读取负载和 CRC
        byte[] payload = new byte[payloadLen + 4];
        currentFile.readFully(payload);

        // 反序列化并验证 CRC
        return LogRecord.deserialize(concat(header, payload));
    }
}
```

## 8. RecoveryManager 实现

### 8.1 ARIES 三阶段恢复

```
崩溃恢复流程:
┌─────────────────────────────────────────────────────────┐
│  Phase 1: Analysis (分析阶段)                           │
│  - 从最近检查点开始扫描日志                              │
│  - 重建活跃事务表 (ATT)                                  │
│  - 重建脏页表 (DPT)                                      │
├─────────────────────────────────────────────────────────┤
│  Phase 2: Redo (重做阶段)                               │
│  - 从 DPT 中最小 LSN 开始                                │
│  - 重做所有已记录的操作                                  │
│  - 恢复到崩溃时的状态                                    │
├─────────────────────────────────────────────────────────┤
│  Phase 3: Undo (撤销阶段)                               │
│  - 撤销所有未提交事务的操作                              │
│  - 按 LSN 倒序处理                                       │
│  - 写入 CLR 记录防止重复撤销                             │
└─────────────────────────────────────────────────────────┘
```

### 8.2 核心数据结构

```java
public class RecoveryManager {

    // 活跃事务表 (Active Transaction Table)
    // txnId -> TransactionEntry { lastLsn, undoNextLsn, status }
    private final Map<Long, TransactionEntry> activeTxnTable;

    // 脏页表 (Dirty Page Table)
    // PageId -> recLsn (首次修改时的 LSN)
    private final Map<PageId, Long> dirtyPageTable;

    // 缓冲池引用 (用于读写页面)
    private final BufferPool bufferPool;
}
```

### 8.3 Analysis 阶段

```java
private void analysisPhase(long checkpointLsn) {
    // 从检查点恢复初始状态
    if (checkpointLsn > 0) {
        restoreFromCheckpoint(checkpointLsn);
    }

    // 扫描日志
    try (LogReader reader = new LogReader(logDir, checkpointLsn)) {
        while (reader.hasNext()) {
            LogRecord record = reader.next();

            switch (record.getType()) {
                case BEGIN:
                    activeTxnTable.put(record.getTxnId(),
                        new TransactionEntry(record.getLsn()));
                    break;

                case COMMIT:
                case ABORT:
                    activeTxnTable.remove(record.getTxnId());
                    break;

                case INSERT:
                case DELETE:
                case UPDATE:
                case PAGE_INIT:
                case PAGE_SPLIT:
                    // 更新事务表
                    updateTxnEntry(record);
                    // 更新脏页表
                    updateDirtyPageTable(record);
                    break;
            }
        }
    }
}
```

### 8.4 Redo 阶段

```java
private void redoPhase() {
    // 找到最小的 recLsn
    long minLsn = dirtyPageTable.values().stream()
        .min(Long::compareTo).orElse(0L);

    try (LogReader reader = new LogReader(logDir, minLsn)) {
        while (reader.hasNext()) {
            LogRecord record = reader.next();

            if (!record.getType().needsRedo()) {
                continue;
            }

            PageId pageId = new PageId(record.getSpaceId(), record.getPageNo());

            // 检查是否需要 redo
            Long recLsn = dirtyPageTable.get(pageId);
            if (recLsn == null || record.getLsn() < recLsn) {
                continue;  // 页面不脏或已经 redo 过
            }

            // 检查页面 LSN
            long pageLsn = getPageLsn(pageId);
            if (record.getLsn() <= pageLsn) {
                continue;  // 页面已经包含此操作
            }

            // 执行 redo
            redoRecord(record);
        }
    }
}
```

### 8.5 Undo 阶段

```java
private void undoPhase() {
    // 收集所有需要撤销的事务
    PriorityQueue<UndoEntry> undoQueue = new PriorityQueue<>(
        (a, b) -> Long.compare(b.lsn, a.lsn)  // LSN 倒序
    );

    for (TransactionEntry entry : activeTxnTable.values()) {
        undoQueue.add(new UndoEntry(entry.txnId, entry.lastLsn));
    }

    // 按 LSN 倒序撤销
    while (!undoQueue.isEmpty()) {
        UndoEntry entry = undoQueue.poll();
        LogRecord record = readRecord(entry.lsn);

        if (record.getType().needsUndo()) {
            // 执行 undo
            undoRecord(record);

            // 写入 CLR
            writeCLR(record);
        }

        // 继续处理前一条日志
        if (record.getPrevLsn() > 0) {
            undoQueue.add(new UndoEntry(entry.txnId, record.getPrevLsn()));
        }
    }

    // 写入 END 记录
    for (Long txnId : activeTxnTable.keySet()) {
        writeEndRecord(txnId);
    }
}
```

## 9. WalBTree 集成

### 9.1 自动日志记录

```java
public class WalBTree {
    private final BTree btree;
    private final WalManager walManager;

    public boolean insert(byte[] recordData, byte[] searchKey,
                         MiniTransaction mtr) throws Exception {
        long txnId = walManager.beginTransaction();

        try {
            // 执行插入
            boolean result = btree.insert(recordData, searchKey, mtr);

            if (result) {
                // 记录日志
                var searchResult = btree.search(searchKey, mtr);
                walManager.logInsert(txnId,
                    btree.getMetadata().getSpaceId(),
                    searchResult.getPageId().getPageNo(),
                    searchResult.getRecordOffset(),
                    recordData);
            }

            walManager.commitTransaction(txnId);
            return result;
        } catch (Exception e) {
            walManager.rollbackTransaction(txnId);
            throw e;
        }
    }
}
```

## 10. 配置和使用

### 10.1 初始化

```java
// 创建 WAL 组件
LogBuffer logBuffer = new LogBuffer("/data/wal", "wal");
WalManager walManager = new WalManager("/data/wal", "wal");

// 崩溃恢复
RecoveryManager recovery = new RecoveryManager(
    bufferPool, "/data/wal", "wal");
recovery.recover();

// 集成到 B+Tree
WalBTree walBTree = new WalBTree(btree, walManager);
```

### 10.2 事务使用模式

```java
try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
    // 插入操作（自动记录 WAL）
    walBTree.insert(recordData, searchKey, mtr);

    // 搜索操作（无 WAL）
    var result = walBTree.search(searchKey, mtr);

    mtr.commit();
}
```

## 11. 关键不变量

### 11.1 WAL 协议

1. **Write-Ahead**: 日志必须在数据页刷盘之前持久化
2. **Force-at-Commit**: 事务提交时必须刷盘
3. **Undo-Redo**: 需要撤销的操作必须记录旧值

### 11.2 LSN 一致性

1. 页面 LSN ≤ 日志最大 LSN
2. 脏页 recLsn ≤ 页面当前 LSN
3. 事务 lastLsn 指向该事务最后一条日志

### 11.3 恢复正确性

1. Analysis 后：ATT 包含所有未提交事务
2. Redo 后：数据库状态 = 崩溃时状态
3. Undo 后：所有未提交事务的效果被撤销

## 12. 文件组织

```
data/
├── wal/
│   ├── wal_000001.log
│   ├── wal_000002.log
│   └── wal_000003.log
└── tablespace/
    └── *.ibd
```

## 13. 性能考虑

### 13.1 日志缓冲

- 使用 64KB 缓冲区减少 I/O 次数
- Group Commit: 多个事务共享一次刷盘

### 13.2 检查点

- 定期执行检查点减少恢复时间
- Fuzzy Checkpoint: 允许检查点期间继续操作

### 13.3 日志轮转

- 单文件 16MB 上限
- 旧日志可在检查点后删除
