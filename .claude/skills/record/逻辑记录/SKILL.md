# Claude Skill: InnoDB COMPACT 记录格式——逻辑记录（Logical Record）设计指南
version: 1.1
scope: mini-db / storage-engine / record-layer
language: zh-CN

## 目标
你将作为存储引擎实现助手，帮助我在 mini-db 中实现 **InnoDB COMPACT 行格式**相关的“记录层（record layer）”。
本 Skill 覆盖 **逻辑记录（logical record）** 的完整语义、字段含义、比较方式、类组织方式，以及其与 COMPACT 物理记录的职责边界。

你必须遵循：
- **逻辑记录与物理记录严格分层**：逻辑记录不包含页内偏移、链指针、slot 等信息。
- 物理格式仅实现 **COMPACT**（不实现 REDUNDANT）。
- 所有索引比较、插入定位必须基于 **逻辑记录 compare**。

---

## 一、InnoDB 中“逻辑记录”的官方语义

### 1. 两层记录视角
InnoDB 明确区分：
- **逻辑记录（dtuple / dfield）**：字段集合、类型、比较语义
- **物理记录（rec_t, COMPACT）**：页内二进制布局

**关键原则**：
> B+Tree、索引查找、插入定位全部基于逻辑记录；
> 物理记录只是逻辑记录在 page 中的一种编码结果。

---

## 二、逻辑记录（dtuple）的组成与字段含义

### 1. dtuple 的语义
逻辑记录本质是：
> 一组有类型、有顺序、有比较语义的字段集合

### 2. 核心字段含义

#### n_fields
- 逻辑记录包含的字段总数
- 聚簇索引：所有列
- 二级索引：索引列 + 主键列

#### n_fields_cmp（关键）
- 用于比较的字段数量
- 决定索引查找、唯一性判断
- 满足：`n_fields_cmp <= n_fields`

示例（二级索引）：
- fields = [a, b, pk]
- n_fields_cmp = 2

#### info_bits
- 逻辑层标志位（如 delete_mark）
- 第一阶段可仅保留结构，不强制使用

---

## 三、逻辑字段（dfield）的语义

### 字段属性
- type：字段类型描述
- len：字段长度（NULL 用特殊值）
- data：数据 slice（可零拷贝）
- is_null：是否 NULL
- is_external：是否外部存储（BLOB/TEXT）

### NULL 与 external
- NULL：逻辑层必须可表达，比较规则固定
- external：逻辑层仅表达“外部存储”，不负责 I/O

---

## 四、逻辑记录的比较方式（核心语义）

比较规则：
1. 仅比较前 `n_fields_cmp` 个字段
2. 按字段顺序逐一比较
3. 使用字段类型对应的 comparator
4. 任一字段不等即返回
5. 全部相等返回 0

建议 NULL 顺序：
- NULL < NOT NULL

---

## 五、逻辑记录与索引的关系

### IndexDescriptor（必须存在）
逻辑记录的比较永远基于索引视角：
- 索引字段顺序
- n_fields_cmp
- 是否 unique / secondary

**DataTuple + IndexDescriptor = 可比较索引记录**

---

## 六、类的组织方式（贴近 InnoDB 思想）

### 原始思想抽象
- dtuple_t
- dfield_t
- dtype_t

### Java 化推荐组织

- FieldType
  - kind
  - fixedLength
  - nullable
  - comparator()

- DataField
  - FieldType type
  - ByteSlice data
  - int len
  - boolean isNull
  - boolean isExternal

- DataTuple
  - DataField[] fields
  - int nFields
  - int nFieldsCmp
  - int infoBits
  - compare(other, IndexDescriptor)

- IndexDescriptor
  - FieldType[] keyTypes
  - int nFieldsCmp
  - boolean unique

---

## 七、逻辑记录 vs COMPACT 物理记录：责任边界

| 能力 | 逻辑记录 | 物理记录 |
|----|----|----|
| 字段值表达 | ✓ | ✗ |
| 字段比较 | ✓ | ✗ |
| NULL / external 语义 | ✓ | ✗ |
| 页内偏移 | ✗ | ✓ |
| next_record | ✗ | ✓ |
| slot directory | ✗ | ✓ |

---

## 八、与 B+Tree 的强制约束

- B+Tree search/insert 必须基于 DataTuple.compare
- page 内二分：slot → record → decode tuple → compare
- split/merge 只搬物理记录，排序与校验基于逻辑 key

禁止：
- 在 B+Tree 中直接解析 byte[] 做比较

---

## 九、推荐实现顺序

### Stage 1：纯逻辑层
- FieldType / DataField / DataTuple
- IndexDescriptor
- compare 单测

### Stage 2：COMPACT 编解码
- CompactRecordWriter / Reader
- tuple → rec → tuple round-trip
- NULL / 变长字段

### Stage 3：页内组织（B+Tree 前置）
- page 插入 record
- slot directory + 二分
- 查找正确性验证

---

## 十、对模型的强制要求

当你被要求设计或实现：
- 记录层
- B+Tree
- page split
- redo/undo 中的记录内容

你必须：
1. 先构造 DataTuple
2. 通过 compare 决策
3. 仅在 I/O 时进入 COMPACT 编解码

END

