
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

强制 不要每写一个phase就编写一个文件，最后写完一个功能比如mvcc再修改实现计划,或者我让你写你再编写，我自己编译，自己测试


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

## Final Rule

If any step above is skipped, the implementation must be discarded as unsafe.
