## 二次复查结论（你已修复的点）

* LogFlusher 已补齐 `LockSupport` 导入，避免编译错误：[LogFlusher.java](file:///c:/coding/java/self/miniproject/miniproject/mini-db/src/main/java/cn/zhangyis/minidb/storage/redo/writer/LogFlusher.java#L9-L13)。

* `syncFlush()` 已改为“通知后台 + 等待 flushedSn 推进”，不再在用户线程里直接 fsync：[LogFlusher.java](file:///c:/coding/java/self/miniproject/miniproject/mini-db/src/main/java/cn/zhangyis/minidb/storage/redo/writer/LogFlusher.java#L254-L290)。

* LogWriter 的 partial-block 合并逻辑已从“读磁盘 block”改为“从 buffer 读前缀数据”，减少 IO：[LogWriter.java](file:///c:/coding/java/self/miniproject/miniproject/mini-db/src/main/java/cn/zhangyis/minidb/storage/redo/writer/LogWriter.java#L255-L281) + [RedoLogBuffer.getData](file:///c:/coding/java/self/miniproject/miniproject/mini-db/src/main/java/cn/zhangyis/minidb/storage/redo/buffer/RedoLogBuffer.java#L504-L589)。

## 仍然存在的问题（需要继续修）

1. LogWriter 批次快照不一致：`writeBatch(startSn,endSn)` 内部用 `buffer.getWriteReadyData()` 取全量快照，可能覆盖到 `endSn` 之后的数据，但最后只 `advanceWriteSn(endSn)`，会造成重复写/顺序错位风险：[LogWriter.java](file:///c:/coding/java/self/miniproject/miniproject/mini-db/src/main/java/cn/zhangyis/minidb/storage/redo/writer/LogWriter.java#L240-L307)。
2. partial-block 合并的新风险：当 block 前缀已被 flush 且 buffer 环绕覆盖后，`getData(blockStartSn,offset)` 会返回 null 并直接抛 fatal；但此时前缀其实应从 redo 文件读取（因为已经持久化）。当前逻辑会把“可恢复场景”误判为致命错误：[LogWriter.java](file:///c:/coding/java/self/miniproject/miniproject/mini-db/src/main/java/cn/zhangyis/minidb/storage/redo/writer/LogWriter.java#L255-L271)。
3. flush 模式语义仍偏差：LogFlusher 主循环只要 `writeSn>flushedSn` 就 fsync，等价于高频刷盘，无法体现 flush=0/2 的“定期 fsync”语义：[LogFlusher.java](file:///c:/coding/java/self/miniproject/miniproject/mini-db/src/main/java/cn/zhangyis/minidb/storage/redo/writer/LogFlusher.java#L160-L175)。
4. checkpoint 口径仍不对：

   * `calculateCheckpointLsn()` 没有做 `min(oldestDirtyLsn, flushedLsn)`，可能返回一个尚未落盘的 LSN；

   * `shouldForceCheckpoint()` 仍用 log buffer 容量当作 redo 空间容量；

   * checkpoint 后没有任何 redo 文件空间回收步骤：[CheckpointManager.java](file:///c:/coding/java/self/miniproject/miniproject/mini-db/src/main/java/cn/zhangyis/minidb/storage/redo/checkpoint/CheckpointManager.java#L193-L309)。
5. recovery 校验不足：Scanner 注释写“验证 checksum”，但 `isValidBlock()` 只看 blockNo/dataLen，不校验 trailer checksum，遇到坏块可能误解析：[RedoLogScanner.java](file:///c:/coding/java/self/miniproject/miniproject/mini-db/src/main/java/cn/zhangyis/minidb/storage/redo/recovery/RedoLogScanner.java#L181-L233)。

## 下一步改动计划（确认后我会动手改代码）

### 1) 修正 LogWriter 的“范围一致性”与 partial-block 回退

* 给 `RedoLogBuffer` 增加/调整 API：让 writer 能以 (startSn,endSn) 精确取 payload（或者返回带 start/end 的快照对象）。

* partial-block 合并策略改为：

  * 优先从 buffer 取前缀；若已不在 buffer（sn\<flushedSn），则从 `fileSet.readBlock()` 读取已落盘的 block 前缀。

### 2) 让 LogFlusher 按 flush=0/1/2 正确决策

* flush=1：收到写入通知后尽快 fsync（仍由后台线程做），提交线程只等待。

* flush=2：commit 只需等待写入 OS cache（由 writer 推进 writeSn），fsync 走定时。

* flush=0：写入/刷盘都走定时（允许最多 1 秒丢失的语义）。

### 3) 修正 Checkpoint 口径并补齐回收接口

* `checkpoint_lsn = min(oldestDirtyLsn, flushedLsn)`。

* `shouldForceCheckpoint()` 改为使用 redo 文件环容量（logFileSize\*fileCount 等）而不是 bufferSize。

* 在 `RedoLogFileSet`/checkpoint 流程中补齐“回收推进”占位实现（即使先是逻辑推进，也要保证接口和水位口径正确）。

### 4) 加强恢复：按 block checksum 判定 last valid lsn

* 使用 `LogBlockFormatter.verifyChecksum()` 或等价逻辑校验 header/trailer。

* 坏块处理策略：停止扫描并以 last valid lsn 为准（避免把损坏当有效 redo）。

### 5) 验证

* 增加单测覆盖：并发提交下写入范围一致、partial-block 前缀在 buffer/不在 buffer 两种场景；flush 模式下 fsync 次数与期望一致；恢复遇坏块能停。

