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

## 13) MTR Usage Pattern (Reference)

```java
try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
    Page page = mtr.getPage(pageId);
    page.putInt(offset, value);
    mtr.markDirty(page);
    mtr.commit();
}
```
