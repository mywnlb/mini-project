
  ---
  Catalog 实现计划

  整体架构

  cn.zhangyis.minidb.storage.catalog/
  ├── CatalogManager.java          // 全局 Catalog 管理器（入口）
  ├── DatabaseDescriptor.java      // 数据库描述符
  ├── TableDescriptor.java         // 表描述符
  ├── ColumnDescriptor.java        // 列描述符（Catalog 级）
  ├── IndexDescriptor.java         // 索引描述符（Catalog 级，包装 btree.IndexDescriptor）
  ├── IdGenerator.java             // 全局 ID 分配器（tableId, columnId, indexId）
  ├── persist/
  │   ├── CatalogMetaPage.java     // Catalog 元数据页（类似 IndexMetaPage）
  │   ├── TableMetaPage.java       // 表元数据页
  │   └── CatalogBootstrap.java    // 引导加载逻辑
  └── cache/
      └── CatalogCache.java        // 内存缓存（读优化）

  与现有代码的对接关系

  CatalogManager
    ├── 管理 → DatabaseDescriptor（库 → 表映射）
    ├── 管理 → TableDescriptor
    │     ├── 持有 → SchemaRegistry（已有，表级 Schema 版本管理）
    │     ├── 持有 → spaceId → TableSpace（已有，物理空间管理）
    │     └── 持有 → indexId 列表 → IndexManager（已有，索引管理）
    ├── 管理 → IdGenerator（统一分配 tableId, columnId, indexId）
    └── 持久化 → CatalogMetaPage / TableMetaPage（仿 IndexMetaPage 模式）

  ---
  Phase 1：核心数据结构

  目标：定义 Catalog 域模型，建立 tableId → 表元信息的完整映射

  1.1 DatabaseDescriptor

  public class DatabaseDescriptor {
      private final int databaseId;
      private final String databaseName;
      private final String charset;           // 默认 "utf8mb4"
      private final long createTime;
      private final Map<String, Long> tableNameToId;  // tableName → tableId
  }

  1.2 TableDescriptor

  这是整个 Catalog 的核心，桥接所有现有模块：

  public class TableDescriptor {
      // === 标识 ===
      private final long tableId;             // 全局唯一，由 IdGenerator 分配
      private final String tableName;
      private final int databaseId;

      // === 物理映射 ===
      private final int spaceId;              // 对应 TableSpace.spaceId

      // === Schema ===
      private final SchemaRegistry schemaRegistry;  // 复用已有的 SchemaRegistry
      private final List<CatalogColumnDescriptor> columns;  // Catalog 级列定义

      // === 索引 ===
      private long primaryIndexId;            // 聚簇索引 ID
      private final List<Long> secondaryIndexIds;  // 二级索引 ID 列表

      // === 元信息 ===
      private final long createTime;
      private volatile long lastUpdateTime;
      private volatile TableState state;      // ACTIVE / DROPPING / DROPPED
  }

  关键设计点：
  - SchemaRegistry 直接复用，不重新发明
  - spaceId 直接对应现有 TableSpace
  - 索引 ID 列表指向现有 IndexManager 管理的索引

  1.3 CatalogColumnDescriptor

  Catalog 级的列定义，比 record.schema.ColumnDescriptor 多出 DDL 相关信息：

  public class CatalogColumnDescriptor {
      private final long columnId;        // 全局唯一，由 IdGenerator 分配
      private final String columnName;
      private final FieldType fieldType;  // 复用已有的 FieldType
      private final int ordinal;          // 列序号
      private final boolean nullable;
      private final byte[] defaultValue;  // 默认值（nullable 时为 null）
      private final boolean isPrimaryKey;
      private final boolean autoIncrement;
  }

  与 record.schema.ColumnDescriptor 的关系：
  - Catalog 级是"定义"（包含默认值、是否主键等 DDL 信息）
  - Record 级是"运行时"（只关心类型、长度、序号）
  - CatalogColumnDescriptor 提供 toRecordColumnDescriptor() 转换方法

  1.4 IdGenerator

  public class IdGenerator {
      private final AtomicLong nextTableId;
      private final AtomicLong nextColumnId;
      private final AtomicLong nextIndexId;
      private final AtomicLong nextDatabaseId;

      // 持久化到 CatalogMetaPage 的固定偏移位置
      public long allocateTableId();
      public long allocateColumnId();
      public long allocateIndexId();
  }

  不变式：ID 值必须在分配后立即持久化（写入 CatalogMetaPage + redo log），防止崩溃后 ID 重复。

  ---
  Phase 2：持久化层

  目标：将 Catalog 信息持久化到系统表空间，仿照 IndexMetaPage 的成熟模式

  2.1 存储位置

  使用系统表空间（spaceId = 0）的保留页面：

  系统表空间 (space 0) 页面分配：
    Page 0: FSP Header Page（已有）
    Page 1: IBUF Bitmap（已有）
    Page 2: Inode Page（已有）
    Page 3: Catalog Meta Page（新增）← ID 生成器 + 数据库列表
    Page 4+: Table Meta Pages（新增）← 表定义，链式存储

  2.2 CatalogMetaPage

  +------------------+
  | Page Header (38) |
  +------------------+
  | Catalog Header   |
  |  - magic (4)     |  0x43415441 ("CATA")
  |  - version (4)   |
  |  - nextTableId (8)|
  |  - nextColId (8) |
  |  - nextIdxId (8) |
  |  - nextDbId (4)  |
  |  - dbCount (4)   |
  |  - tableMetaPage (4) | ← 第一个 TableMetaPage 的页号
  +------------------+
  | Database Entry 1 |
  |  - dbId (4)      |
  |  - nameLen (2)   |
  |  - name (var)    |
  |  - charset (var) |
  |  - tableCount(4) |
  +------------------+
  | Database Entry 2 |
  +------------------+

  2.3 TableMetaPage

  类似 IndexMetaPage，每个 entry 是一个表的完整定义：

  +------------------+
  | Page Header (38) |
  +------------------+
  | Meta Header (16) |
  |  - magic (4)     |  0x5441424C ("TABL")
  |  - version (4)   |
  |  - count (4)     |
  |  - nextPage (4)  |
  +------------------+
  | Table Entry 1    |
  |  - entryLen (4)  |
  |  - tableId (8)   |
  |  - dbId (4)      |
  |  - spaceId (4)   |
  |  - nameLen + name|
  |  - columnCount(4)|
  |  - columns...    |
  |  - indexCount (4)|
  |  - indexIds...   |
  |  - createTime(8) |
  |  - state (1)     |
  +------------------+
  | Table Entry 2    |
  +------------------+

  2.4 CatalogBootstrap

  数据库启动时的引导流程：

  1. 打开系统表空间（space 0）
  2. 读取 Page 3（CatalogMetaPage）
  3. 恢复 IdGenerator 的计数器
  4. 遍历所有 DatabaseEntry → 构建 DatabaseDescriptor
  5. 遍历 TableMetaPage 链 → 构建 TableDescriptor
  6. 对每个 TableDescriptor：
     a. 从列定义重建 RecordSchema + SchemaRegistry
     b. 通过 IndexManager 加载关联的索引
     c. 打开对应的 TableSpace
  7. 填充 CatalogCache

  ---
  Phase 3：CatalogManager（内存层）

  目标：提供上层（Handler API / SQL 层）访问元数据的统一接口

  public class CatalogManager {

      private final BufferPool bufferPool;
      private final IdGenerator idGenerator;
      private final CatalogCache cache;

      // === 数据库操作 ===
      DatabaseDescriptor createDatabase(String name);
      DatabaseDescriptor getDatabase(String name);
      void dropDatabase(String name);
      List<DatabaseDescriptor> listDatabases();

      // === 表操作（DDL）===
      TableDescriptor createTable(String dbName, String tableName,
                                  List<CatalogColumnDescriptor> columns,
                                  List<IndexDefinition> indexes);
      void dropTable(String dbName, String tableName);
      TableDescriptor getTable(String dbName, String tableName);
      TableDescriptor getTableById(long tableId);
      List<TableDescriptor> listTables(String dbName);

      // === Schema 查询（DML 时调用）===
      RecordSchema getCurrentSchema(long tableId);
      SchemaRegistry getSchemaRegistry(long tableId);

      // === 启动/关闭 ===
      void bootstrap();  // 首次初始化（创建元数据页）
      void loadCatalog(); // 从磁盘加载
      void close();
  }

  CatalogCache

  public class CatalogCache {
      // 读路径的快速查找，使用 ConcurrentHashMap
      private final Map<Long, TableDescriptor> tableById;
      private final Map<String, Map<String, TableDescriptor>> tableByDbAndName;
      private final Map<String, DatabaseDescriptor> databaseByName;

      // 读不加锁，写时用细粒度锁（per-database 或 per-table）
  }

  并发策略：
  - 读：直接查 ConcurrentHashMap，无锁
  - 写（DDL）：获取对应 database 的写锁，保证同一库内 DDL 串行
  - 不同库的 DDL 可并发

  ---
  Phase 4：CREATE TABLE 完整流程

  这是最关键的 DDL 操作，串联所有模块：

  CREATE TABLE db1.users (
      id INT PRIMARY KEY,
      name VARCHAR(100),
      age INT
  )

  执行流程：
  1. CatalogManager.createTable("db1", "users", columns, indexes)
  2. IdGenerator 分配 tableId, columnId×3
  3. 创建 TableSpace（分配 spaceId，创建 .ibd 文件）
  4. 构建 RecordSchema（从 CatalogColumnDescriptor 转换）
  5. 构建 SchemaRegistry，注册 version=0 的 Schema
  6. 通过 IndexManager 创建聚簇索引（PRIMARY）
  7. 组装 TableDescriptor
  8. 持久化：
     a. 写入 TableMetaPage（MTR 保护）
     b. 更新 CatalogMetaPage 的 ID 计数器（MTR 保护）
  9. 更新 CatalogCache
  10. 返回 TableDescriptor

  原子性保证：步骤 6-8 在同一个用户事务内完成。如果任何步骤失败：
  - 回滚 IndexManager 的创建
  - 删除 .ibd 文件
  - 不更新 CatalogCache

  ---
  Phase 5：与现有模块的集成

  5.1 修改 ColumnDescriptor（record/schema）

  当前 ColumnDescriptor.of() 使用 name hash 作为临时 columnId。改为：
  - 由 Catalog 分配 columnId 后传入构造器
  - 删除 hash 临时方案

  5.2 Handler API（后续模块，本次只定义接口）

  public interface TableHandler {
      void open(TableDescriptor table);
      void close();
      void insert(Transaction txn, DataTuple row);
      DataTuple get(Transaction txn, byte[] primaryKey);
      void update(Transaction txn, byte[] primaryKey, DataTuple newRow);
      void delete(Transaction txn, byte[] primaryKey);
      RowIterator scan(Transaction txn);
      RowIterator rangeScan(Transaction txn, byte[] startKey, byte[] endKey);
  }

  内部通过 TableDescriptor 获取 BTree、RecordSchema、TransactionalDml 等。

  5.3 修改 TransactionalDml

  当前构造器直接接收 RecordSchema。改为：
  - 通过 CatalogManager.getCurrentSchema(tableId) 获取
  - 或者由 Handler 层传入（推荐，避免 TransactionalDml 直接依赖 Catalog）

  ---
  实现顺序建议

  ┌──────┬──────────────────────────────────────────────────────────────┬──────────────────────────────────┬────────┐
  │ 步骤 │                             内容                             │               依赖               │ 文件数 │
  ├──────┼──────────────────────────────────────────────────────────────┼──────────────────────────────────┼────────┤
  │ P1-1 │ IdGenerator                                                  │ 无                               │ 1      │
  ├──────┼──────────────────────────────────────────────────────────────┼──────────────────────────────────┼────────┤
  │ P1-2 │ DatabaseDescriptor, TableDescriptor, CatalogColumnDescriptor │ P1-1                             │ 3      │
  ├──────┼──────────────────────────────────────────────────────────────┼──────────────────────────────────┼────────┤
  │ P2-1 │ CatalogMetaPage（持久化 ID + DB 列表）                       │ P1-1                             │ 1      │
  ├──────┼──────────────────────────────────────────────────────────────┼──────────────────────────────────┼────────┤
  │ P2-2 │ TableMetaPage（持久化表定义）                                │ P1-2                             │ 1      │
  ├──────┼──────────────────────────────────────────────────────────────┼──────────────────────────────────┼────────┤
  │ P2-3 │ CatalogBootstrap（引导加载）                                 │ P2-1, P2-2                       │ 1      │
  ├──────┼──────────────────────────────────────────────────────────────┼──────────────────────────────────┼────────┤
  │ P3-1 │ CatalogCache                                                 │ P1-2                             │ 1      │
  ├──────┼──────────────────────────────────────────────────────────────┼──────────────────────────────────┼────────┤
  │ P3-2 │ CatalogManager（createDatabase, getDatabase）                │ P2-1, P3-1                       │ 1      │
  ├──────┼──────────────────────────────────────────────────────────────┼──────────────────────────────────┼────────┤
  │ P3-3 │ CatalogManager（createTable, dropTable, getTable）           │ P2-2, P3-2                       │ 同上   │
  ├──────┼──────────────────────────────────────────────────────────────┼──────────────────────────────────┼────────┤
  │ P4-1 │ CREATE TABLE 完整流程                                        │ P3-3 + TableSpace + IndexManager │ 集成   │
  ├──────┼──────────────────────────────────────────────────────────────┼──────────────────────────────────┼────────┤
  │ P4-2 │ 修改 ColumnDescriptor columnId 分配                          │ P1-1                             │ 改动   │
  ├──────┼──────────────────────────────────────────────────────────────┼──────────────────────────────────┼────────┤
  │ P5-1 │ Handler API 接口定义                                         │ P3-3                             │ 1-2    │
  ├──────┼──────────────────────────────────────────────────────────────┼──────────────────────────────────┼────────┤
  │ 测试 │ 单元测试 + 集成测试                                          │ 每个 Phase                       │ 3-5    │
  └──────┴──────────────────────────────────────────────────────────────┴──────────────────────────────────┴────────┘

  ---
  关键设计决策总结

  ┌────────────────┬────────────────────────────────────────┬──────────────────────────────────┐
  │     决策点     │                  选择                  │               原因               │
  ├────────────────┼────────────────────────────────────────┼──────────────────────────────────┤
  │ 元数据存储方式 │ 固定布局元数据页（仿 IndexMetaPage）   │ 避免鸡生蛋问题，引导自包含       │
  ├────────────────┼────────────────────────────────────────┼──────────────────────────────────┤
  │ 元数据页位置   │ 系统表空间 Page 3+                     │ 参考 InnoDB，系统表空间保留页    │
  ├────────────────┼────────────────────────────────────────┼──────────────────────────────────┤
  │ Schema 管理    │ 复用 SchemaRegistry                    │ 已有完善的版本管理，不重复造轮子 │
  ├────────────────┼────────────────────────────────────────┼──────────────────────────────────┤
  │ ID 分配        │ 集中式 AtomicLong + 持久化             │ 简单可靠，避免分布式 ID 的复杂性 │
  ├────────────────┼────────────────────────────────────────┼──────────────────────────────────┤
  │ 并发策略       │ 读无锁 ConcurrentHashMap，写 per-DB 锁 │ DML 读密集，DDL 低频             │
  ├────────────────┼────────────────────────────────────────┼──────────────────────────────────┤
  │ DDL 原子性     │ 用户事务级（不用 2PC）                 │ 单机数据库，不需要分布式事务     │
  └────────────────┴────────────────────────────────────────┴──────────────────────────────────┘

  ---
  有什么想调整的方向，或者要开始实现哪个 Phase？
