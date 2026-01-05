# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is mini-db, a Java implementation of an InnoDB-like relational database kernel. It's an enterprise-grade database kernel project aimed at modeling, validating, and evolving core database internals such as storage engines, buffer pool management, transaction systems, and query execution frameworks.

## Key Architecture Documents

Before making changes, review the comprehensive architecture documentation in the `md/` directory:

- **md/context.md**: Complete system architecture with Mermaid diagrams, data flow paths, module responsibilities, and implementation phases
- **md/instructions.md**: Core design principles, technology stack, coding standards, and implementation guidelines

## Build & Test Commands

The project uses Gradle with Java 21. Common commands:

```bash
# Navigate to project root (parent of mini-db)
cd ..

# Build the project
gradle build

# Run tests for mini-db module
gradle :mini-db:test

# Run a specific test class
gradle :mini-db:test --tests "ClassName"

# Clean build
gradle clean build
```

Note: The project requires Java 21 (`sourceCompatibility = '21'`).

## High-Level Architecture

The codebase follows a layered InnoDB-like architecture:

### Current Implementation Status (Early Phase)

The project is in **Phase 1: Basic Storage Infrastructure**. The storage layer foundation exists:

```
cn.zhangyis.minidb.storage/
├── buffer/          # Buffer Pool implementation (COMPLETE)
│   ├── BufferPool   - Main buffer pool with LRU, Free List, Flush List
│   ├── BufferFrame  - Individual page frames with pin/unpin
│   ├── LRUList      - Young-Old partitioned LRU
│   ├── FreeList     - Free frame management
│   └── FlushList    - Dirty page tracking by LSN
├── page/            # Page structure (COMPLETE)
│   ├── Page         - Base 16KB page with FIL header/trailer
│   ├── IndexPage    - B+Tree index page implementation
│   ├── PageId       - (spaceId, pageNo) identifier
│   └── PageType     - Page type enumeration
├── disk/            # Disk I/O (COMPLETE)
│   └── DiskManager  - Physical page read/write operations
├── mtr/             # Mini-Transaction (COMPLETE)
│   └── MiniTransaction - Page operation atomicity and lifecycle management
└── StorageConstants - Page size, offsets, constants
```

### Planned Architecture (Future Phases)

See `md/context.md` for the complete target architecture including:

- **Client/Protocol Layer**: MySQL protocol, connection management, session handling
- **SQL Layer**: Lexer, Parser (AST), Binder (semantic binding), Rewriter, Optimizer (RBO/CBO)
- **Execution Engine**: Volcano iterator model with operators (SeqScan, IndexScan, Filter, Project, Join, Aggregate, Sort)
- **Catalog**: Database/Table/Column/Index metadata and statistics
- **Transaction System**: TransactionManager, MVCC with ReadView, LockManager, deadlock detection
- **Log & Recovery**: Redo Log (WAL), Undo Log (version chains), Checkpoint, Crash Recovery
- **Background Threads**: Purge, Flush, Checkpoint, IO Scheduler

## Critical Implementation Details

### Mini-Transaction (MTR) - **REQUIRED** Usage Pattern

**IMPORTANT**: All page operations MUST be wrapped in MTR for proper resource management.

MTR (Mini-Transaction) is the standard way to interact with Buffer Pool. It provides:
- Automatic page pin/unpin management
- Atomicity of page operations
- Dirty page tracking
- Redo log generation (when implemented)

**Recommended Pattern (try-with-resources)**:
```java
try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
    // Get pages (automatically pinned)
    Page page1 = mtr.getPage(pageId1);
    Page page2 = mtr.newPage(spaceId);

    // Modify pages
    page1.putInt(offset, value);
    page2.putBytes(offset, data);

    // Mark as dirty (REQUIRED after modification)
    mtr.markDirty(page1);
    mtr.markDirty(page2);

    // Commit (generates redo log, unpins all pages)
    mtr.commit();
} // Auto-rollback if commit not called
```

**Critical MTR Rules**:
1. **Never call BufferPool.getPage() directly** - always use MTR
2. **Always call mtr.markDirty()** after modifying a page
3. **Use try-with-resources** for automatic cleanup
4. **One MTR per thread** - MTR is not thread-safe
5. **Commit or rollback** before MTR closes

**MTR Lifecycle**:
- `ACTIVE` → can get/modify pages
- `COMMITTED` → changes persisted, pages unpinned, redo log written
- `ABORTED` → changes discarded, pages unpinned

**Example: Multi-page atomic operation**:
```java
// Example: B+Tree node split - must be atomic
try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
    Page oldNode = mtr.getPage(oldNodeId);
    Page newNode = mtr.newPage(spaceId);
    Page parentNode = mtr.getPage(parentId);

    // Split logic...
    splitNode(oldNode, newNode);
    updateParent(parentNode, newNode.getPageId());

    mtr.markDirty(oldNode);
    mtr.markDirty(newNode);
    mtr.markDirty(parentNode);

    mtr.commit(); // All 3 pages updated atomically
}
```

### Buffer Pool Design

The BufferPool is the centerpiece of the storage engine:

**Key Concepts**:
- **Page Hash**: O(1) lookup via `ConcurrentHashMap<PageId, frameIndex>`
- **LRU List**: Young-Old partitioned LRU for page replacement
- **Free List**: Tracks unused frames
- **Flush List**: Dirty pages sorted by LSN for checkpoint
- **Pin/Unpin**: Reference counting prevents eviction of in-use pages

**Direct Usage Pattern** (NOT recommended - use MTR instead):
```java
BufferFrame frame = bufferPool.getPage(pageId, FetchMode.READ_EXISTING);
try {
    Page page = frame.getPage();
    // Modify page...
} finally {
    bufferPool.unpinPage(pageId, isDirty);  // MUST unpin!
}
```

**Critical Rule**: Every `getPage()` call MUST have a matching `unpinPage()`, or pages will never be evicted (buffer pool exhaustion). **Use MTR to avoid manual pin/unpin management.**

### Page Format (16KB)

Pages follow InnoDB's physical layout:

```
[FIL Header 38B] [Page Body 16298B] [FIL Trailer 8B]
```

- **FIL Header**: checksum, page_no, prev/next (for linked lists), LSN, page_type, space_id
- **FIL Trailer**: checksum copy + LSN low 32 bits (detects partial writes)
- **Byte Order**: Little Endian throughout
- **Checksum**: CRC32 over bytes 4-16375

**Page Types** (see PageType enum):
- `FIL_PAGE_INDEX` (17855): B+Tree index pages
- `FIL_PAGE_TYPE_ALLOCATED` (0): Freshly allocated, uninitialized

### Data Flow: Page Read with MTR

```
mtr.getPage(pageId)
  → Check MTR memo (already fetched?)
    → YES: return cached page
    → NO: call BufferPool.getPage()
      → PageHash lookup (read lock)
        → HIT: pin++, update LRU → return frame
        → MISS: acquire write lock
          → Double-check PageHash
          → getFreeFrame() (may trigger eviction)
          → DiskManager.readPage()
          → Create Page/IndexPage from ByteBuffer
          → Add to PageHash, LRU Old区
          → pin++, return frame
  → Add to MTR memo
  → Return page

mtr.commit()
  → Generate redo log records
  → For each page in memo (LIFO order):
    → bufferPool.unpinPage(pageId, isDirty)
  → Clear memo
  → State = COMMITTED
```

### Eviction Strategy

When Free List is empty:
1. Get candidate from LRU tail (oldest in Old region)
2. Skip if pinned (try next)
3. If dirty: flush to disk via `DiskManager.writePage()`
4. Remove from PageHash, LRU, FlushList
5. Reset frame, return index

## Coding Standards

From `md/instructions.md`:

- **Comments**: Comprehensive Javadoc for all classes, methods, fields. Complex logic needs inline explanation.
- **Thread Safety**: Use virtual threads where possible (Java 21). Explicit synchronization for shared state (e.g., BufferPool's poolLock).
- **Error Handling**: Custom exceptions for different error types (referenced but not yet implemented).
- **Logging**: SLF4J + Logback for key operations and errors (configured but not widely used yet).
- **Testing**: JUnit for unit tests. Each module should have corresponding tests (partially implemented).
- **Design Patterns**: Interface-based design (e.g., Handler API planned). Avoid tight coupling.

## Important Notes

1. **Package Imports**: Current code has incorrect import statements (e.g., `import com.minidb.storage.*` should be `import cn.zhangyis.minidb.storage.*`). Fix these when encountered.

2. **Multi-Module Project**: This is part of a larger multi-project repository. Other modules (mini-mysql, mini-spring, etc.) were deleted but remain in git history. Focus only on mini-db.

3. **Implementation Phase**: The project is at the very beginning. Buffer Pool, Page structure, and MTR exist, but no B+Tree, no transactions, no SQL parsing. Refer to the phase roadmap in `md/context.md` Section 7 for implementation order.

4. **InnoDB Simplifications**: See `md/context.md` Section 8 for what's simplified vs real MySQL InnoDB:
   - Fixed 16KB pages (not configurable)
   - Single Buffer Pool instance (no multi-instance)
   - Basic B+Tree (no full SMO)
   - Simplified Redo/Undo logs
   - Row locks only (no gap/next-key locks initially)
   - RR isolation only

## Development Workflow

When adding new features:

1. **Check Phase**: Verify it aligns with the implementation roadmap (context.md Section 7)
2. **Design First**: Complex modules (optimizer, MVCC) should sketch design based on context.md architecture
3. **Interface-Driven**: Define interfaces before implementations
4. **Use MTR**: All page operations must use MiniTransaction
5. **Test Coverage**: Write JUnit tests for new components
6. **Documentation**: Update context.md if architecture changes

## Next Implementation Steps

Based on Phase roadmap:

**Phase 1 (Current)**: ✅ Catalog + Page + BufferPool + MTR
- TODO: Basic B+Tree implementation
- TODO: Simple TableScan
- TODO: Basic SQL Parser (CREATE TABLE, INSERT, SELECT)

**Phase 2**: Storage Engine Core
- B+Tree (clustered index)
- Handler API
- Redo/Undo Log infrastructure

**Phase 3**: Transaction System
- TransactionManager
- MVCC + ReadView
- Row locks
- Deadlock detection

## References

The project draws from:
- "MySQL Internals: InnoDB Storage Engine" (姜承尧)
- "High Performance MySQL" (Baron Schwartz)
- "Database System Implementation" (Garcia-Molina)
- MySQL 8.0 source code (https://github.com/mysql/mysql-server)
- InnoDB documentation
