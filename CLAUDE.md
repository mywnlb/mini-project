
# CLAUDE.md (Kernel-Safe Version for mini-db)

# 🚨 Fail-First Design Gate (MANDATORY)

This project enforces a Fail-First design discipline.

When a request contains:
- "Task:"
- OR asks to implement / design any kernel or storage component

Claude MUST follow this order BEFORE any Kernel-Safe workflow:

## Step 0: Fail-First Gate (Design Level)

Claude MUST output, BEFORE reading md docs or writing code:

### Forbidden Designs
- At least 2 designs or implementation paths that MUST NOT be used
- For each:
   - Why it is dangerous
   - Which later module / invariant / recovery path will break
   - Failure type (silent corruption / crash / recovery failure)

### Core Invariants (Design Level)
- At least 3 invariants that define correctness of this task
- These invariants must be referenced later in implementation

If this Fail-First Gate is skipped:
→ All following implementation is INVALID and must be discarded.

Only after this gate passes:
→ Proceed to Kernel-Safe Mode.

This project is NOT a normal Java project.
It is a DATABASE KERNEL project.

Correctness depends on:
- invariants
- alignment rules
- wrap-around math
- happens-before relations (JMM)
- physical page / log layout
- latch/lock ordering

If these are violated, the system will suffer SILENT CORRUPTION.

强制 不要每写一个phase就编写一个说明文件，我让你写你再编写，我自己编译，自己测试
强制 永远用中文回答

Claude MUST follow the rules below.

---

## ✅ Kernel-Safe Mode Trigger (MANDATORY)

At the start of every response involving storage modules, Claude MUST begin with:

Kernel-Safe Mode: ON
Module: <name>
Will produce: (A) Module Context Snapshot, (B) Invariants, (C) Implementation, (D) Tests

---

## 🚨 Mandatory Pre-Read Rule

Before modifying ANY code under:

cn.zhangyis.minidb.storage.*

Claude MUST read design documents from md/.

### Automatic Module Documentation Discovery

1. Extract module name from package path:
   storage.redo → redo
   storage.buffer → buffer
   storage.page → page
   storage.mtr → mtr
   storage.index → index
   storage.extent → extent
   storage.tablespace → tablespace

2. Search md/ for filenames containing this module name.

---

## 📌 Context Loading Policy (MANDATORY)

Claude MUST NOT load large documents blindly.

When modifying a storage module:

1. Always read:
   - md/context.md
   - md/instructions.md

2. Load module docs using keyword-first policy:
   - Read top 1–3 most relevant md files
   - Expand only if constraints unclear

3. Output BEFORE coding:

### Module Context Snapshot
- Module:
- Files read:
- Top invariants (≤8)
- Non-obvious pitfalls (≤5)
- Silent corruption risks (≤3)

If missing → implementation invalid.

---

## 🔒 Concurrency, Latch/Lock & Memory Safety (MANDATORY)

Claude MUST specify:

1. Latch/Lock requirements:
   - Page S/X latch?
   - Table/Global lock?
   - Lock ordering rule

2. Deadlock risk scenario and prevention

3. JMM visibility contract:
   - Publication points (volatile/Atomic/fence)
   - Why readers see consistent state

---

## 🚨 Kernel Invariant Rule

Before writing ANY implementation code, Claude MUST output:

### Invariants
1. Invariants required for correctness
2. Alignment / offset / wrap-around rules
3. Happens-before requirements
4. Conditions causing silent corruption
5. Fields existing only to maintain invariants

---

## 🚨 Invariant Enforcement Rule

After writing code, Claude MUST map each invariant to code location.

---

## 🔴 Redo / LogBuffer Absolute Rules

Claude MUST reason about:

- (sn % LOG_BLOCK_DATA_SIZE == 0)
- Continuous 496B slice rule
- Ring buffer tail requirement
- LSN block alignment
- Record group recovery visibility
- Wakeup/wait impact on Group Commit

---

## 🔴 MTR Absolute Rule

Redo is generated at COMMIT time, not modification time.

---

## 🔴 Ring Buffer Rule

Must define:
- head
- tail
- reuse condition
- overwrite condition

---

## 🧮 Step 4.5: Symbolic Trace / Math Check (MANDATORY)

For offset/alignment/LSN math:
- Provide one concrete dry-run
- Include boundary case
- Show intermediate values

---

## 🧪 Testing Requirement (MANDATORY)

Claude MUST propose:

1. ≥3 unit tests for boundary / wrap-around / partial cases
2. Concurrency test if shared state touched
3. State which invariant each test validates

---

## Required Workflow

1. Read md docs
2. Module Context Snapshot
3. Invariants
4. Corruption scenarios
5. Symbolic trace
6. Implementation
7. Tests

---

## MTR Usage Pattern

All page operations MUST use MTR.

```java
try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
    Page page = mtr.getPage(pageId);
    page.putInt(offset, value);
    mtr.markDirty(page);
    mtr.commit();
}
```

---

## 🔗 系统启动依赖链（MANDATORY）

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

## Final Rule
If any step above is skipped, the implementation must be discarded as unsafe.

在使用 Ralph Loop 模式时，如果连续 5次 测试失败且报错信息没有变化，请立即停止并请求人类介入。
每次循环必须先执行 git commit -m "ralph: iteration X" 以便回滚。

---

## 本地编译 / 测试环境（MANDATORY）

在这个仓库里执行编译或测试时，固定使用下面这套环境，不要依赖 PATH 自动解析：

- `JAVA_HOME=C:\Program Files\Java\jdk-21`
- `GRADLE_USER_HOME=C:\gradlereportiry`
- `Gradle=D:\worker\gradle71\gradle-8.5-bin\gradle-8.5\bin\gradle.bat`

PowerShell 推荐写法：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:GRADLE_USER_HOME='C:\gradlereportiry'
D:\worker\gradle71\gradle-8.5-bin\gradle-8.5\bin\gradle.bat :mini-db:test
```

强制规则：

1. 不要默认使用 `gradlew` / `gradlew.bat`，仓库当前可能缺失可用 wrapper 运行环境
2. 不要使用 PATH 中旧版 `gradle`，旧版本会与 Java 21 不兼容
3. 需要跑单测时，使用：

```powershell
D:\worker\gradle71\gradle-8.5-bin\gradle-8.5\bin\gradle.bat :mini-db:test --tests "全限定测试类名"
```
