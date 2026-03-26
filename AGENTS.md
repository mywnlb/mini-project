# AGENTS.md

Project: mini-db

This document defines mandatory rules for AI coding agents (Codex, Claude Code, Copilot CLI, or similar tools).
This repository is a storage kernel similar to MySQL InnoDB. Prioritize correctness over convenience.

If applicable rules are skipped, the result is unsafe.

---

## 1) Scope And Trigger

These rules are mandatory when a task can change behavior under:

- `cn.zhangyis.minidb.storage.*`
- storage format, WAL/redo behavior, crash recovery behavior, lock/latch behavior

Read-only explanations are allowed to use a lighter format, but must still respect invariants and safety claims.

---

## 2) Mandatory Pre-Read (Before Editing Code)

Before modifying any code under `cn.zhangyis.minidb.storage.*`, the agent must read:

1. `md/context.md`
2. `md/instructions.md`
3. 1-3 module-relevant docs from `md/`

The agent must avoid loading large documentation blindly.

If no module-specific doc is found by filename, search by keyword in `md/` content and list the files used.

---

## 3) Module Documentation Discovery

Extract module names from package paths and search `md/` with these keywords first:

| Package Prefix | Module | Search Keywords |
| --- | --- | --- |
| `storage.redo` | redo | `redo`, `wal`, `log` |
| `storage.buffer` | buffer | `buffer`, `bufferpool` |
| `storage.page` | page | `page` |
| `storage.mtr` | mtr | `mtr`, `mini transaction` |
| `storage.record` | record | `record`, `compact` |
| `storage.btree` | btree | `btree`, `index` |
| `storage.space` | space | `space`, `extent`, `segment`, `tablespace` |
| `storage.transaction` | transaction | `transaction`, `mvcc`, `undo`, `purge` |
| `storage.disk` | disk | `disk`, `io` |
| `storage.constants` | constants | `constant`, `layout`, `format` |

---

## 4) Kernel-Safe Response Format

For storage implementation/design tasks, the first relevant response must start with:

`Kernel-Safe Mode: ON`
`Module: <module-name>`

And include these sections:

A. Module Context Snapshot
B. Invariants
C. Implementation
D. Tests

For read-only tasks, sections can be shortened, but module context and invariants must still be explicit.

---

## 5) Fail-First Design Gate (Mandatory For Design/Implementation)

Before writing code, output:

### Forbidden Designs

At least 2 approaches that must NOT be used. For each:

- why it is dangerous
- which module/invariant it breaks
- failure type: silent corruption, crash, or recovery failure

### Core Invariants (Design Level)

At least 3 invariants that define correctness for this task.
These invariants must be referenced again during implementation and tests.

---

## 6) Required Invariant Set Before Coding

Before implementation, define:

1. correctness invariants
2. alignment rules
3. wrap-around rules (if counters/ring offsets exist)
4. happens-before / visibility requirements
5. silent-corruption conditions
6. fields used only to maintain invariants

---

## 7) Concurrency, Locking, And Memory Safety

When shared state exists, explicitly define:

- latch/lock requirements (`page S-latch`, `page X-latch`, global/table locks if applicable)
- lock ordering to prevent deadlock
- deadlock risk scenarios and prevention
- JMM contract (`volatile`, atomic classes, fences/happens-before)

---

## 8) Redo / MTR / Ring Buffer Rules

For redo-related changes, reason about:

- LSN alignment
- log block boundaries
- continuous log slices
- ring buffer layout
- recovery visibility
- group commit ordering

Example alignment invariant:

`sn % LOG_BLOCK_DATA_SIZE == 0`

For MTR-related changes, follow current project semantics and clearly state when redo is generated and when it becomes durable.

For ring buffers, define:

- head pointer
- tail pointer
- reuse condition
- overwrite condition

Never allow overwrite of unflushed data.

---

## 9) Symbolic Trace / Math Check

For any alignment or offset arithmetic, provide:

1. one concrete example
2. one boundary case
3. intermediate values

---

## 10) Invariant Enforcement Mapping

After implementation, map each invariant to the exact code location that enforces it.

---

## 11) Testing Requirements

For behavior-changing storage work, provide:

1. at least 3 unit tests
2. concurrency tests when shared state is involved
3. invariant each test validates

If the change is purely non-behavioral (rename/refactor/comments), state why reduced testing is acceptable.

---

## 12) Required Workflow

For applicable storage design/implementation tasks:

1. Read docs (`context.md`, `instructions.md`, module docs)
2. Module Context Snapshot
3. Fail-First Design Gate
4. Invariants and corruption scenarios
5. Symbolic trace (if math/layout involved)
6. Implementation
7. Invariant-to-code mapping
8. Tests

Skipping mandatory steps invalidates the solution.

---

## 13) 系统启动依赖链（MANDATORY）

修改任何组件前，必须理解该组件在启动链中的位置及上下游依赖。
不允许跳过上游初始化，不允许在链外单独创建已纳入链内的组件。

```
DatabaseBootstrap.start() 统一编排以下阶段：

Phase 1: DiskManager
  └→ createTablespace / openTablespace
  └→ 首次创建时必须调用 TableSpace.initializeTablespace(mtr)
     （初始化 FSP Header: nextSegmentId=1, INODE Page, Extent 等物理结构）

Phase 2: BufferPool
  └→ 依赖 DiskManager
  └→ 后台 LRU 整理线程随构造启动（页面少时精度低属正常）

Phase 3: Redo Recovery（可选）
  └→ 依赖 BufferPool
  └→ 必须在 Catalog 加载前完成（否则读到过期页面）

Phase 4: CatalogManager.bootstrap()
  └→ 依赖 BufferPool + 系统表空间已初始化
  └→ 首次启动: CatalogBootstrap.initCatalog() 初始化 page 3/4/5
  └→ 后续启动: loadCatalog() 加载快照 + DDL log replay

Phase 5: UndoLogManager → TransactionManager
  └→ 依赖 BufferPool + 系统表空间 FSP Header 已初始化
  └→ UndoLogManager 构造时会 createSegment → allocateSegmentId
     （要求 nextSegmentId ≥ 1，未初始化的表空间会导致 Segment ID=0 拒绝）
  └→ TransactionManager 依赖 UndoLogManager

Phase 6: MiniDbServer（MySQL 协议层）
  └→ 依赖 CatalogManager + TransactionManager
  └→ MysqlConnectionHandler 在认证时动态创建 StorageDataSource
     （需要 ExecutionContext.txnManager() 非 null）
  └→ dataSource 允许为 null（由 handler 按连接动态创建）
```

### 依赖链规则
1. **不允许在 Bootstrap 外部手动创建链内组件**（如 TransactionManager）
2. **新建表空间 = 创建文件 + 初始化物理结构**，两步缺一不可
3. **修改任一 Phase 前，必须先读该 Phase 上下游的实现代码**
4. **配置项集中在 minidb.yml，由 ServerConfig 加载，MiniDbServerMain 传递**

---

## 14) MTR Usage Pattern (Reference)

```java
try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
    Page page = mtr.getPage(pageId);
    page.putInt(offset, value);
    mtr.markDirty(page);
    mtr.commit();
}
```

---

## 15) 本地编译 / 测试环境（MANDATORY）

在本仓库执行编译或测试时，默认使用以下本机环境，不要依赖 PATH 中的旧版本工具：

- `JAVA_HOME=C:\Program Files\Java\jdk-21`
- `GRADLE_USER_HOME=C:\gradlereportiry`
- `Gradle=D:\worker\gradle71\gradle-8.5-bin\gradle-8.5\bin\gradle.bat`

执行 Gradle 前必须先设置上述环境变量。

PowerShell 推荐写法：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:GRADLE_USER_HOME='C:\gradlereportiry'
D:\worker\gradle71\gradle-8.5-bin\gradle-8.5\bin\gradle.bat :mini-db:test
```

额外规则：

1. **不要优先使用 `gradlew` / `gradlew.bat`**，当前仓库缺少可用的 wrapper 运行环境时会直接失败
2. **不要使用 PATH 中默认的旧版 `gradle`**，本机旧版本曾因 Java 21 classfile 不兼容导致构建失败
3. 如果只跑单个测试，使用：

```powershell
D:\worker\gradle71\gradle-8.5-bin\gradle-8.5\bin\gradle.bat :mini-db:test --tests "全限定测试类名"
```
