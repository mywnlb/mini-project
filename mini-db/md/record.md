# 逻辑记录 (Logical Record) 设计文档

## 一、架构概览

### 1.1 分层架构

```
┌─────────────────────────────────────────────────────────────┐
│                    Logical Layer（逻辑层）                    │
│  DataTuple / DataField / FieldType / FieldKind              │
│  （内存中的行数据表示，与格式无关）                              │
├─────────────────────────────────────────────────────────────┤
│                    Format Layer（格式层）                     │
│  RecordFormat (接口) + 工厂模式                               │
│  ├── CompactRecordFormat (默认)                              │
│  ├── DynamicRecordFormat                                     │
│  └── RedundantRecordFormat                                   │
├─────────────────────────────────────────────────────────────┤
│                    Physical Layer（物理层）                   │
│  RecordHeader / SystemLayout / RecordAnchor                  │
│  （页面内的字节布局）                                          │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 类结构

```
cn.zhangyis.minidb.storage.record/
├── RecordHeader.java            # 5 字节记录头
│
├── logical/
│   ├── DataTuple.java           # 逻辑行（dtuple_t）
│   ├── DataField.java           # 逻辑字段（dfield_t）
│   └── TupleBuilder.java        # 构建器
│
├── schema/
│   ├── FieldKind.java           # 字段类型枚举
│   ├── FieldType.java           # 字段类型描述（dtype_t）
│   ├── ColumnDescriptor.java    # 列描述符
│   ├── RecordSchema.java        # 某个 rowVersion 对应的 schema 快照
│   ├── SchemaRegistry.java      # rowVersion → RecordSchema 注册表
│   └── InstantColumnMeta.java   # 列级 Instant 元数据
│
├── format/
│   ├── RecordFormat.java        # 格式接口
│   ├── RecordFormatType.java    # 格式枚举
│   ├── RecordFormatFactory.java # 格式工厂
│   ├── CompactRecordFormat.java # 默认 Compact 格式
│   ├── DynamicRecordFormat.java # Dynamic 格式
│   ├── FieldOffsets.java        # 解析后的字段偏移表
│   └── OverflowPolicy.java      # 溢出策略参数化
│
├── physical/
│   └── SystemLayout.java        # 系统列布局
│
└── reader/
    └── RowReader.java           # 统一读取入口
```

---

## 二、物理布局

### 2.1 锚点定义

```
recStart (唯一锚点)
    │
    ▼
┌─────────────────────────────────────────────────────────────────┐
│  [变长字段长度列表] [NULL bitmap] │ Record Header (5B) │ Data...  │
│  ◄──────── 向左增长 ──────────────►│◄── dataStart() ───►│         │
└─────────────────────────────────────────────────────────────────┘
```

**关键约束**：
- `recStart` = Record Header 起始位置（唯一锚点）
- `dataStart() = recStart + 5`
- Page Directory slot 存储 `recStart`
- `next_record` 是相对 `recStart` 的偏移量

### 2.2 记录头 (5 Bytes)

```
┌─────────┬─────────┬──────────────────┬───────────────┬────────────────┐
│ 4 bits  │ 4 bits  │     13 bits      │    3 bits     │    16 bits     │
│ info_   │ n_owned │     heap_no      │   rec_type    │  next_record   │
│ bits    │         │                  │               │  (相对偏移)     │
└─────────┴─────────┴──────────────────┴───────────────┴────────────────┘
```

**info_bits 含义**：
- Bit 0: `REC_INFO_DELETED_FLAG` - 删除标记（MVCC 软删除）
- Bit 1: `REC_INFO_MIN_REC_FLAG` - B+Tree 非叶节点最小记录

**rec_type 类型**：
- 0: `REC_ORDINARY` - 普通用户记录
- 1: `REC_NODE_PTR` - B+Tree 非叶节点指针
- 2: `REC_INFIMUM` - 虚拟最小记录
- 3: `REC_SUPREMUM` - 虚拟最大记录

### 2.3 系统列布局（聚簇索引）

```
                        recStart
                            │
                            ▼
┌───────────────────────────┬──────────────────────────────────────────────────────┐
│ [varlen list] [NULL bits] │ Header(5B) │ TRX_ID │ ROLL_PTR │ ROW_VER │ [ROW_ID] │ user cols... │
│ ◄────── 向左增长 ─────────►│            │  (6B)  │   (7B)   │  (2B)   │  (6B)    │              │
└───────────────────────────┴──────────────────────────────────────────────────────────────────────┘
                                         │
                                    dataStart()
                                         │
                                         ├─ +0:  TRX_ID (恒定)
                                         ├─ +6:  ROLL_PTR (恒定)
                                         ├─ +13: ROW_VER (恒定)
                                         ├─ +15: ROW_ID (可选，由 layout.hasRowId 决定)
                                         └─ +15 or +21: 用户列起始
```

### 2.4 SystemLayout

```java
/**
 * 系统列布局（由 index 元数据决定）
 *
 * I7: hasRowId 是 index 级元数据，创建后不可变
 */
public record SystemLayout(boolean hasRowId) {

    // 固定偏移（相对 dataStart）
    public static final int OFF_TRX_ID = 0;
    public static final int OFF_ROLL_PTR = 6;
    public static final int OFF_ROW_VER = 13;

    /** ROW_ID 偏移，不存在时返回 -1 */
    public int offRowId() {
        return hasRowId ? 15 : -1;
    }

    /** 系统列总字节数 */
    public int fixedSysBytes() {
        return hasRowId ? 21 : 15;
    }

    /** 用户列起始偏移（相对 dataStart） */
    public int userColumnsOffset() {
        return fixedSysBytes();
    }
}
```

---

## 三、Compact 行格式

### 3.1 完整布局

```
◄─────────────────────── 向左增长 ────────────────────────►
┌──────────────────────┬───────────────┬────────────────────┬─────────────────────────┐
│ 变长字段长度列表      │ NULL 标志位   │ Record Header (5B) │ 记录数据                 │
│ (逆序存储)           │ (1 bit/列)    │                    │                         │
└──────────────────────┴───────────────┴────────────────────┴─────────────────────────┘
                                       ↑
                                    recStart
```

### 3.2 变长字段长度列表

- **逆序存储**：最后一个变长列的长度放在最前面
- **编码规则**：
  - 长度 < 128：1 字节，最高位 = 0
  - 长度 >= 128：2 字节，最高位 = 1，大端序

### 3.3 NULL 标志位

- 每个 Nullable 列占 1 bit
- 按列声明顺序排列
- 向上取整到字节边界
- 只包含 Nullable 列，NOT NULL 列不占位

---

## 四、核心接口

### 4.1 RecordFormat

```java
public interface RecordFormat {

    /**
     * O(1) 读取 row version（不解析 offsets）
     * 位置固定：dataStart() + OFF_ROW_VER
     */
    int peekRowVersion(ByteBuffer page, int recStart);

    /**
     * 解析变长字段长度列表 + NULL bitmap → 字段偏移表
     *
     * I8: 返回的 offset 相对 dataStart，不含系统列
     */
    FieldOffsets parseOffsets(ByteBuffer page, int recStart, RecordSchema schema);

    /**
     * 编码写入目标页面（避免 byte[] 拷贝）
     * @return 写入的总字节数
     */
    int encodeTo(ByteBuffer page, int recStart, DataTuple tuple,
                 RecordSchema schema, SystemLayout layout);

    /**
     * 解码为逻辑 Tuple（不含 Instant 默认值填充）
     */
    DataTuple decode(ByteBuffer page, int recStart, FieldOffsets offsets,
                     RecordSchema schema, SystemLayout layout);

    /**
     * 计算编码后所需空间（含 header、系统列、用户列）
     */
    int calculateSize(DataTuple tuple, RecordSchema schema, SystemLayout layout);

    RecordFormatType getType();
}
```

### 4.2 RowReader

```java
public final class RowReader {
    private final SchemaRegistry registry;
    private final RecordFormatFactory factory;

    public DataTuple read(IndexPage page, int recStart, SystemLayout layout) {
        // 1. 由 index 元数据决定格式（I4: 不是每行切换）
        RecordFormat fmt = factory.forIndex(page.getIndexId());

        // 2. O(1) peek row version（I5: 记录自描述）
        int rowVersion = fmt.peekRowVersion(page.getBuffer(), recStart);

        // 3. 获取该版本对应的 schema
        RecordSchema schema = registry.get(rowVersion);

        // 4. 解析 offsets（I8: 相对 dataStart，不含系统列）
        FieldOffsets offsets = fmt.parseOffsets(page.getBuffer(), recStart, schema);

        // 5. 解码用户字段
        DataTuple tuple = fmt.decode(page.getBuffer(), recStart, offsets, schema, layout);

        // 6. 列级填充 Instant 默认值（I6: 按 columnId 填充）
        return registry.fillInstantDefaults(tuple, rowVersion);
    }
}
```

### 4.3 RecordFormatFactory

```java
public enum RecordFormatType {
    REDUNDANT(0, "Redundant"),    // MySQL 4.0 格式
    COMPACT(1, "Compact"),        // MySQL 5.0 默认
    DYNAMIC(2, "Dynamic"),        // MySQL 5.7 默认（大字段溢出）
    COMPRESSED(3, "Compressed");  // 压缩格式（暂不支持）
}

public class RecordFormatFactory {
    private static final RecordFormat COMPACT = new CompactRecordFormat();

    // I4: 格式由 index 元数据决定
    public RecordFormat forIndex(long indexId) {
        RecordFormatType type = indexMetaStore.getFormatType(indexId);
        return create(type);
    }

    public static RecordFormat create(RecordFormatType type) {
        return switch (type) {
            case COMPACT -> COMPACT;
            case DYNAMIC -> new DynamicRecordFormat();
            case REDUNDANT -> new RedundantRecordFormat();
            case COMPRESSED -> throw new UnsupportedOperationException("COMPRESSED not supported");
        };
    }

    public static RecordFormat getDefault() {
        return COMPACT;
    }
}
```

---

## 五、Instant DDL 支持

### 5.1 InstantColumnMeta

```java
/**
 * 列级 Instant 元数据
 *
 * F5: 以 columnId 为主键，columnIndex 只在快照内有效
 */
public record InstantColumnMeta(
    long columnId,            // 长期稳定标识（主键）
    int introducedVersion,    // 引入该列的 rowVersion
    byte[] defaultBytes,      // 默认值序列化
    FieldType type            // 用于反序列化默认值
) {
    public DataField getDefaultValue() {
        return DataField.deserialize(defaultBytes, type);
    }
}
```

### 5.2 SchemaRegistry

```java
public class SchemaRegistry {
    // rowVersion → 对应的 schema 快照
    private final Map<Integer, RecordSchema> schemas = new ConcurrentHashMap<>();

    // 当前表的最新 schema
    private volatile RecordSchema currentSchema;

    // 列级 Instant 元数据
    private final List<InstantColumnMeta> instantColumns = new ArrayList<>();

    /**
     * I6: 按列级 introducedVersion + columnId 填充默认值
     */
    public DataTuple fillInstantDefaults(DataTuple tuple, int rowVersion) {
        if (rowVersion >= currentSchema.getVersion()) {
            return tuple;
        }

        for (InstantColumnMeta meta : instantColumns) {
            if (meta.introducedVersion() > rowVersion) {
                // columnId → 当前 schema 中的 index 映射
                int index = currentSchema.indexOfColumn(meta.columnId());
                if (index >= 0) {
                    tuple.setField(index, meta.getDefaultValue());
                }
            }
        }
        return tuple;
    }
}
```

---

## 六、使用方式

### 6.1 创建逻辑记录

```java
// 方式1：构建器模式
DataTuple tuple = TupleBuilder.create()
    .add(DataField.intField(1001))              // id
    .add(DataField.varcharField("张三"))         // name
    .add(DataField.bigintField(25L))            // age
    .add(DataField.nullField(FieldKind.VARCHAR)) // nullable column
    .build();

// 方式2：从 Schema 构建
RecordSchema schema = RecordSchema.builder()
    .version(1)
    .column("id", FieldKind.INT, false)
    .column("name", FieldKind.VARCHAR, 100, false)
    .column("age", FieldKind.BIGINT, true)
    .build();

DataTuple tuple = DataTuple.fromValues(schema, 1001, "张三", 25L);
```

### 6.2 读取记录

```java
// 通过 RowReader 读取（推荐）
RowReader reader = new RowReader(schemaRegistry, formatFactory);
DataTuple tuple = reader.read(indexPage, recStart, systemLayout);

// 手动读取流程
RecordFormat format = RecordFormatFactory.getDefault();
int rowVersion = format.peekRowVersion(page.getBuffer(), recStart);
RecordSchema schema = schemaRegistry.get(rowVersion);
FieldOffsets offsets = format.parseOffsets(page.getBuffer(), recStart, schema);
DataTuple tuple = format.decode(page.getBuffer(), recStart, offsets, schema, layout);
tuple = schemaRegistry.fillInstantDefaults(tuple, rowVersion);
```

### 6.3 写入记录

```java
try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
    IndexPage page = (IndexPage) mtr.getPage(pageId);
    RecordFormat format = RecordFormatFactory.getDefault();
    SystemLayout layout = indexMeta.getSystemLayout();
    RecordSchema schema = schemaRegistry.getCurrentSchema();

    // 1. 计算空间
    int totalSize = format.calculateSize(tuple, schema, layout);

    if (page.getFreeSpace() < totalSize) {
        // 触发页分裂
    }

    // 2. 分配空间
    int recStart = page.allocateRecord(totalSize);

    // 3. 写入（rowVersion = currentSchema.version）
    format.encodeTo(page.getBuffer(), recStart, tuple, schema, layout);

    // 4. 维护链表和 Page Directory
    page.insertIntoRecordList(recStart, prevRecStart);

    mtr.markDirty(page);
    mtr.commit();  // redo log 包含完整记录内容
}
```

---

## 七、Invariants

| # | 约束 | 说明 |
|---|------|------|
| **I1** | Page Directory slot 指向 owned record 的 recStart，且 nOwned > 0 | slot 值 = recStart |
| **I2** | next_record 是相对 recStart 的偏移，infimum→supremum 链永远可遍历 | 遍历验证终止于 supremum |
| **I3** | TRX_ID/ROLL_PTR 在 dataStart 后的位置恒定（+0, +6） | 不因 ROW_ID 可选而变化 |
| **I4** | row format 是 index 级元数据，不是每行切换 | RecordFormatFactory.forIndex() |
| **I5** | rowVersion 从记录 peek，不由调用者传入 | RowReader 流程强制 |
| **I6** | Instant 默认值按列级 introducedVersion + columnId 填充 | InstantColumnMeta 结构 |
| **I7** | SYS_ROW_VERSION 存在性是 index 级元数据，不可运行时切换 | SystemLayout.hasRowId 不可变 |
| **I8** | FieldOffsets 基准 = 相对 dataStart，不含系统列 | 用户列从 `dataStart + layout.fixedSysBytes()` 开始 |

---

## 八、Forbidden Designs

| # | 禁止项 | 原因 |
|---|--------|------|
| **F1** | row_version 塞进 info_bits (4bit) | 位宽不够，破坏 header 扩展空间 |
| **F2** | deserialize 由调用者传 schemaVersion | 传错导致 silent corruption |
| **F3** | 硬编码 768 字节溢出阈值 | 无外部页机制前无法验证 |
| **F4** | 硬编码 OFF_ROW_ID = 15 | ROW_ID 可选时导致分支蔓延 |
| **F5** | InstantColumnMeta.columnIndex 作为长期标识 | 列重排后失效 |
| **F6** | SYS_ROW_VERSION 放在 TRX_ID 之前 | 破坏 I3（系统列位置恒定） |

---

## 九、Simplifications

| 简化 | 说明 | 后续扩展点 |
|------|------|-----------|
| 只支持聚簇索引 | 二级索引记录布局不同（key + PK columns） | SecondaryRecordFormat |
| 不做 COMPRESSED | 涉及页压缩、redo 影响 | 需要页级压缩框架 |
| Dynamic 溢出策略参数化 | `OverflowPolicy.NEVER` 为默认 | 需要外部页 + 回收机制 |
| Schema 只支持 ADD COLUMN | 不支持 DROP/MODIFY Instant | 需要列重排机制 |

---

## 十、实现阶段

| 阶段 | 内容 | 交付物 |
|------|------|--------|
| **P1** | FieldKind, FieldType, DataField, DataTuple, TupleBuilder | 逻辑层基础 |
| **P2** | RecordHeader, SystemLayout | 物理层基础 |
| **P3** | RecordFormat 接口 + FieldOffsets + CompactRecordFormat | 格式层核心 |
| **P4** | RecordSchema, ColumnDescriptor, SchemaRegistry, InstantColumnMeta | Schema 版本化 |
| **P5** | RowReader + IndexPage 集成 | 读路径打通 |
| **P6** | 写路径（encodeTo）+ 空间计算 | 写路径打通 |
| **P7** | OverflowPolicy + DynamicRecordFormat | 溢出支持（需外部页） |

---

## 十一、读写路径时序

### 11.1 读路径

```
┌─────────────────────────────────────────────────────────────────┐
│  1. 定位 recStart（slot/链/搜索）                                │
├─────────────────────────────────────────────────────────────────┤
│  2. dataStart = recStart + 5                                    │
├─────────────────────────────────────────────────────────────────┤
│  3. rowVersion = peekRowVersion(dataStart + 13) // O(1), u16    │
├─────────────────────────────────────────────────────────────────┤
│  4. schema = schemaRegistry.get(rowVersion)                     │
├─────────────────────────────────────────────────────────────────┤
│  5. offsets = fmt.parseOffsets(page, recStart, schema)          │
│     // 解析 varlen list + NULL bitmap                           │
├─────────────────────────────────────────────────────────────────┤
│  6. tuple = fmt.decode(page, recStart, offsets, schema, layout) │
├─────────────────────────────────────────────────────────────────┤
│  7. if rowVersion < currentSchema.version:                      │
│       for col in instantColumns:                                │
│         if col.introducedVersion > rowVersion:                  │
│           index = currentSchema.indexOfColumn(col.columnId)     │
│           tuple.setField(index, col.defaultValue)               │
└─────────────────────────────────────────────────────────────────┘
```

### 11.2 写路径

```
┌─────────────────────────────────────────────────────────────────┐
│  1. rowVersion = currentSchema.version()  // 固定 u16           │
├─────────────────────────────────────────────────────────────────┤
│  2. 计算空间: header(5) + layout.fixedSysBytes() + userDataSize │
├─────────────────────────────────────────────────────────────────┤
│  3. 分配 recStart，写入 Record Header                            │
├─────────────────────────────────────────────────────────────────┤
│  4. encodeTo 顺序（dataStart 起）:                               │
│    ├─ TRX_ID      (+0,  6B)                                     │
│    ├─ ROLL_PTR    (+6,  7B)                                     │
│    ├─ ROW_VER     (+13, 2B) ← currentSchema.version             │
│    ├─ [ROW_ID]    (+15, 6B) ← 如果 layout.hasRowId              │
│    └─ user cols   (+15 or +21)                                  │
├─────────────────────────────────────────────────────────────────┤
│  5. MTR 标记脏页，生成 redo log                                   │
│    ├─ rowVersion 属于记录内容，必须进入 redo                      │
│    └─ 页重放时整条记录覆盖写入                                    │
├─────────────────────────────────────────────────────────────────┤
│  6. 维护 heap_no++, 链表插入, Page Directory 更新                 │
└─────────────────────────────────────────────────────────────────┘
```
