# Java 类 MySQL / InnoDB 数据库内核 —— 架构说明
## 参考书籍
- 《MySQL技术内幕：InnoDB存储引擎》
- 《高性能MySQL》
- 《数据库系统实现》
- 
## 一、整体架构概览

本项目是一个 **Java 实现的类 MySQL / InnoDB 风格关系型数据库内核**，目标是构建一个可演进、可教学、可扩展的数据库系统。

系统整体采用 **分层 + 子系统解耦** 的经典数据库内核架构，主要包括：

- Client / 协议与连接层
- SQL 解析与优化层
- 执行引擎
- 元数据系统（Catalog）
- 事务与并发控制
- 存储引擎（InnoDB-like）
- 日志与恢复系统
- 后台线程与运维能力

设计原则：
- SQL 层与存储引擎解耦
- 事务系统独立
- 面向接口（Handler API）
- 模块化设计，便于替换与扩展
- 尽量使用虚拟线程
- 每个模块需要编写单元测试

代码规范：
- 注释完善，包括类 方法 字段，一些复杂逻辑需要额外说明
- 遵循 Java 编码规范
- 合理使用设计模式
- 模块间通过接口通信，避免直接依赖实现类
- 代码风格统一
- 变量命名有意义
- 异常处理得当，需要时抛出自定义异常
- 日志记录关键操作和错误
- 编写单元测试，确保代码质量
- 多线程编程时注意线程安全，避免竞态条件，死锁等问题，能使用虚拟线程时尽量使用虚拟线程

### 技术栈
- 编程语言：Java 21
- 构建工具：gradle
- netty 用于网络通信
- JUnit 用于单元测试
- 日志框架：SLF4J + Logback
- 持久化文件：本地文件系统
- 数据格式：自定义二进制格式
- 版本控制：Git

---

## 二、Client / 协议层（Client Layer）

### 职责
负责数据库对外通信、连接管理和会话状态维护。

### 核心组件
- **CLI / JDBC Client**
    - 命令行客户端或 JDBC Driver
- **Protocol Layer**
    - MySQL 协议兼容
    - Handshake / Auth / Command 解析
- **Connection Manager**
    - 连接生命周期管理
    - Session 上下文管理

### 设计要点
- 一个连接对应一个 Session
- 兼容mysql通信协议
- Session 保存：
    - 当前数据库
    - 当前事务
    - 系统变量与用户上下文

---

## 三、SQL 层（SQL Layer）

### 处理流程

SQL Text → Lexer → Parser(AST) → Binder → Rewriter → Optimizer → Physical Plan


### 子模块说明

#### 1. Lexer / Parser
- SQL 文本解析为 AST
- 实现方式：
  - 手写解析器（教学）

#### 2. Binder（语义绑定）
- 表、列、索引解析
- 类型推导
- 权限校验
- 参考 apache  Catalog

#### 3. Rewriter（查询重写）
- 常量折叠
- 谓词下推
- 简单子查询改写
- 参考 apache  Catalog

#### 4. Optimizer（优化器）
- 基于规则或代价
- 选择：
  - TableScan / IndexScan
  - Join 顺序
- 依赖统计信息
- 参考 apache  Catalog

---

## 四、执行引擎（Execution Engine）

### 职责
按照执行计划驱动算子运行，产生结果集。

### 执行模型
采用 **Volcano / Iterator Model**



## 四、执行引擎（Execution Engine）

采用 Volcano / Iterator Model。

---

## 五、元数据系统（Metadata / Catalog）

维护 Database / Table / Column / Index / Statistics。
职责

维护数据库的结构信息。

包含内容

Database

Table

Column

Index

Tablespace 映射

统计信息

实现建议

系统表（类似 information_schema）

启动时加载到内存

DDL 修改时同步更新
---

## 六、事务与并发控制（Transaction System）

Transaction Manager + MVCC + Lock Manager。
职责

保证 ACID 中的：

Atomicity

Consistency

Isolation

核心组件
Transaction Manager

Begin / Commit / Rollback

事务 ID 分配

MVCC

Undo Log 版本链

ReadView

一致性读

Lock Manager

行锁

间隙锁（后期）

Next-Key Lock（后期）

Deadlock Detector

Wait-for Graph

超时或主动检测

演进路径

阶段 1：单线程无并发

阶段 2：MVCC + 行锁

阶段 3：完整 InnoDB 并发模型
---

## 七、存储引擎（Storage Engine / InnoDB-like）

Handler API + Buffer Pool + B+Tree + Page + Tablespace。
职责

负责数据的物理存储与访问。

设计原则

SQL 层通过 Handler API 访问

不感知 SQL 语义
子模块说明
Handler API
open()
next()
get()
insert()
update()
delete()

Buffer Pool

Page Cache

LRU 管理

脏页管理

Flush List

B+Tree

聚簇索引

二级索引

页分裂 / 合并

Page / Record

Page Header

Slot Directory

Record Format

Tablespace / File Manager

表空间

数据文件

页分配与回收

---

## 八、日志与恢复（Logging & Recovery）

Redo Log / Undo Log / Checkpoint / Recovery。

保证 Durability，并支持崩溃恢复。

日志类型
Redo Log

WAL

顺序写

页级物理日志

Undo Log

回滚

MVCC 版本构建

Checkpoint

刷脏页

控制恢复时间

Recovery

崩溃恢复流程：

重放 Redo

回滚未提交事务

---

## 九、后台线程（Background Threads）

Purge / Flush / IO Scheduler。

主要线程

Purge Thread

清理历史版本

Flush Thread

刷脏页

IO Scheduler

IO 调度

设计建议

独立线程池

与前台执行解耦

---

## 十、运维与可观测性（Ops）

Metrics / Admin Command。

功能

Metrics

QPS / TPS

Buffer Hit Ratio

Admin Command

SHOW

EXPLAIN

备份与恢复（可选）

---

## 十一、典型 SQL 执行路径

Client → Protocol → SQL → Executor → Transaction → Storage → Log → Commit。

Client
→ Protocol
→ Session
→ SQL Parser
→ Optimizer
→ Executor
→ Transaction Begin
→ Storage Handler
→ Buffer Pool / B+Tree
→ Redo / Undo
→ Commit
→ Result Set

十二、推荐实现顺序

Catalog + TableScan

CREATE TABLE

INSERT

SELECT（无索引）

Buffer Pool

Redo / Undo

MVCC

索引