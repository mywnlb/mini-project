# Storage Regression Plan (Rechecked)

Kernel-Safe Mode: ON  
Module: storage

## A. Module Context Snapshot

- Re-verified against `md/context.md`, `md/instructions.md`, `md/wal-implementation.md`, `md/segment-extent-implementation-status.md`, `md/mvcc.md`, `md/MTR_Architecture.md`, `md/record.md`, `md/btree-architecture.md`, plus the project redo/space/record skills.
- Current full-suite status is misleading by default: `:mini-db:test` fails `33/477` only because test temp directories are created under the Windows default temp path and hit `AccessDeniedException`.
- With `java.io.tmpdir` redirected to a repo-local writable directory, the original temp-path `AccessDeniedException` is gone. Full `:mini-db:test` also passes again after fixing a non-storage `information_schema` prepared-statement hang in the protocol layer.
- Direct tests under `src/test/java/cn/zhangyis/minidb/storage` are almost empty today. Most storage behavior is only covered indirectly from `sql/`.
- One existing fixture is unsafe for kernel tests: `BaseStorageTest` creates a tablespace file but does not run physical initialization. That violates the repository rule that new tablespaces must be both created and initialized.
- `BTree.searchVisible()` is currently a placeholder path, so MVCC visibility should not be tested there as a primary contract.
- `TransactionManager.initializeInMemory()` is valid only for isolated invariants; boot-chain tests must use `DatabaseBootstrap` or explicit physical initialization.

## B. Forbidden Designs And Core Invariants

### Forbidden Designs

- Do not keep relying on default system temp directories. Dangerous because it causes infrastructure crash before any storage signal is observed. Failure type: crash.
- Do not build new storage tests on top of the current `BaseStorageTest` behavior. Dangerous because it skips `TableSpace.initializeTablespace()` and breaks space/segment invariants. Failure type: silent corruption or false regression.
- Do not duplicate SQL-layer scenarios inside storage tests unless the new test adds lower-level invariant assertions. Dangerous because it increases noise without improving kernel diagnosis. Failure type: silent coverage gap.

### Core Invariants

- Startup order is fixed: system space ready -> redo recovery -> catalog bootstrap -> undo/transaction init.
- `nextSegmentId >= 1` is the physical-ready sentinel for initialized tablespaces.
- MTR must release each pinned page exactly once; commit and rollback must not leave partial visible state.
- Redo ring reuse must never overrun unflushed data: `endSn - flushedSn <= capacity`.
- ReadView semantics must stay stable: own writes visible, post-snapshot commits invisible under RR, old versions reachable via Undo.
- `TRX_ID`, `Segment ID`, and redo positions are monotonic and must not wrap into invalid visible state.
- Alignment math must preserve block boundaries: `sn=495 -> lsn=507`, `sn=496 -> lsn=524`.
- Ring wrap must preserve data continuity: `capacity=1024, startSn=960, size=128` must split at the boundary and remain unreadable for reuse until flush advances.

## C. Implementation Changes

- Add a test-only temp-dir fix in `mini-db/mini-db.gradle` for all `Test` tasks, pointing `java.io.tmpdir` to a writable directory under `mini-db/build`.
- Add a shared storage fixture in `mini-db/src/test/java/cn/zhangyis/minidb/storage` that:
  uses repo-local workdirs,
  creates tablespaces safely,
  exposes `initSystemSpace`, `initUserSpace`, `initUndoSpace`, and `bootTransactionSubsystem`.
- Keep and extend the existing `DatabaseBootstrapTest` instead of creating a duplicate bootstrap suite.
- Add focused low-level tests under `.../storage`, not one large monolith:
  `TableSpaceInvariantTest`
  `MiniTransactionInvariantTest`
  `RedoLogBufferInvariantTest`
  `RecordFormatInvariantTest`
  `TransactionManagerInvariantTest`
  `UndoLogManagerInvariantTest`
  `LockManagerInvariantTest`
- Keep existing `sql/` integration tests as outer coverage. New `storage/` tests should assert the underlying kernel state directly instead of moving existing SQL tests wholesale.
- For MVCC, test `ReadView`, `VisibilityChecker`, `TransactionManager`, and `UndoLogManager` directly. Do not make `BTree.searchVisible()` a gating contract in this round.
- For catalog/storage chain coverage, extend `DatabaseBootstrapTest` and add one storage-side catalog smoke test only if a direct gap remains after fixture cleanup.

## D. Tests

- `DatabaseBootstrapTest`: startup chain, first boot, restart, fail-stop, DDL log head, TRX_SYS migration, transaction subsystem init ordering.
- `TableSpaceInvariantTest`: page `0/1/2` layout, free lists, segment create/drop, extent state migration, reopen persistence.
- `MiniTransactionInvariantTest`: commit vs rollback, dirty-page tracking, close-without-commit semantics, repeated page access and release discipline.
- `RedoLogBufferInvariantTest`: `LsnMapper` math, block-boundary cases, ring wrap, no overwrite before flush, LinkBuf hole handling, wait counters/backpressure.
- `RecordFormatInvariantTest`: compact/dynamic round-trip, `ROW_VER` peek, null/varlen offsets, unsupported format rejection.
- `TransactionManagerInvariantTest`: monotonic `TRX_ID`, consistent `ReadView`, commit/rollback state transitions, lock release hook behavior.
- `UndoLogManagerInvariantTest`: undo tablespace initialization, insert/update/delete undo writes, rollback order, history-list/commit transitions.
- `LockManagerInvariantTest`: record/table lock conflict, timeout, `unlockAll` idempotence, one deterministic deadlock case with deadlock detection enabled.
- Acceptance steps:
  `:mini-db:test --tests "cn.zhangyis.minidb.storage.*"`
  `:mini-db:test`

### Current Execution Status (2026-03-26)

- `:mini-db:test --tests "cn.zhangyis.minidb.storage.*"` passes with the repo-local `java.io.tmpdir` fix in place.
- `:mini-db:test` passes after removing the `information_schema` virtual-table row-count recursion that previously caused `COM_STMT_PREPARE` to hang before responding.

## Assumptions

- New regression tests are added under `.../storage`; existing `sql/` tests remain in place.
- This round prioritizes direct kernel invariants over purge/compression deep coverage; those can stay smoke-level unless failures appear.
- The temp-dir fix is part of the plan because without it the current suite cannot produce trustworthy storage results.
