# MySQL 协议接入 + JDBC 驱动实现计划

## Context

mini-db 的 SQL 引擎 7 个扩展方向已全部完成，下一步是让它从"嵌入式库"变成可通过标准 MySQL 客户端（mysql CLI、JDBC）连接的数据库服务。需要：手写 MySQL 协议编解码（纯手写，不用任何协议库）、Netty 网络层、认证、文本/二进制协议、以及自定义 JDBC 驱动。

---

## 架构总览

```
MySQL Client (mysql CLI / JDBC Driver)
     │  TCP
     ▼
Netty Pipeline (NIO EventLoop)
     │
MysqlPacketDecoder → MysqlPacketEncoder
     │
MysqlConnectionHandler（连接状态机）
     │
ConnectionSession（每连接：SqlSession + ExecutionContext + PreparedStatement map）
     │
SqlSession.execute(sql) / PreparedStatement.execute()
     │
现有 SQL 引擎（parse → validate → optimize → execute）
```

---

## 关键集成点

| mini-db 现有 API | 协议层如何对接 |
|---|---|
| `SqlSession.execute(sql)` → `List<Row>` | COM_QUERY 文本协议 |
| `PreparedStatement.setXxx() / execute()` | COM_STMT_PREPARE / COM_STMT_EXECUTE 二进制协议 |
| DML 返回 `Row(Map.of("affected_rows", N))` | 检查 key 判断是否 DML → 发 OkPacket |
| `ExecutionContext.begin/commit/rollback()` | 每连接独立 ExecutionContext，映射事务状态到 MySQL status flags |
| `CatalogSpi.getColumns()` → `List<ColumnMeta>` | 构建 ColumnDefinitionPacket 的列类型 |
| `SqlType` 枚举 (INT32/BIGINT/VARCHAR/DECIMAL/DATETIME) | 映射到 MySQL 列类型码 |
| `Row.columns()` → `LinkedHashMap<String, Object>` | 保序，直接映射为结果集列顺序 |

---

## Phase 1：协议编解码基础（纯字节操作，无网络）

**目标**：手写 MySQL 协议所有包的编解码，纯单元测试验证

### 新建文件

**`server/protocol/MysqlConstants.java`** — 所有协议常量
- 能力标志：`CLIENT_PROTOCOL_41`, `CLIENT_SECURE_CONNECTION`, `CLIENT_PLUGIN_AUTH`, `CLIENT_DEPRECATE_EOF` 等
- 命令字节：`COM_QUERY(0x03)`, `COM_STMT_PREPARE(0x16)`, `COM_STMT_EXECUTE(0x17)`, `COM_STMT_CLOSE(0x19)`, `COM_QUIT(0x01)`, `COM_PING(0x0E)`, `COM_INIT_DB(0x02)`
- 状态标志：`SERVER_STATUS_AUTOCOMMIT`, `SERVER_STATUS_IN_TRANS`
- MySQL 列类型码：`MYSQL_TYPE_LONG(0x03)` → INT32, `MYSQL_TYPE_LONGLONG(0x08)` → BIGINT, `MYSQL_TYPE_VAR_STRING(0xFD)` → VARCHAR, `MYSQL_TYPE_NEWDECIMAL(0xF6)` → DECIMAL, `MYSQL_TYPE_DATETIME(0x0C)` → DATETIME

**`server/protocol/MysqlBufUtil.java`** — 线格式读写工具
- `readFixedLengthInt(ByteBuf, length)` / `writeFixedLengthInt` — 小端定长整数
- `readLengthEncodedInt(ByteBuf)` / `writeLengthEncodedInt` — MySQL 变长编码整数
- `readNullTerminatedString` / `writeNullTerminatedString`
- `readLengthEncodedString` / `writeLengthEncodedString`
- `readFixedLengthString` / `readRestOfPacketString`

**`server/protocol/TypeMapping.java`** — SqlType ↔ MySQL 类型码双向映射 + Java Object → 线格式编码

**`server/protocol/packets/`** — 各包类型（每个手写编解码）

| 文件 | 职责 |
|---|---|
| `HandshakePacket.java` | 服务端握手包（协议版本10、server version、connection id、20字节auth data、能力标志） |
| `HandshakeResponsePacket.java` | 解析客户端认证响应（用户名、auth response、数据库、能力标志） |
| `OkPacket.java` | OK 响应（affected_rows, last_insert_id, status_flags, warnings） |
| `ErrPacket.java` | 错误响应（error_code, sql_state, message） |
| `EofPacket.java` | EOF 标记（warnings, status_flags） |
| `ColumnDefinitionPacket.java` | 列定义（catalog/schema/table/name/类型/flags/decimals） |
| `ResultSetRowPacket.java` | 文本协议行（各列值 length-encoded string，NULL = 0xFB） |
| `BinaryResultSetRowPacket.java` | 二进制协议行（NULL bitmap + 类型化二进制值） |
| `ComQueryPacket.java` | 解码 COM_QUERY |
| `ComStmtPreparePacket.java` | 解码 COM_STMT_PREPARE |
| `ComStmtExecutePacket.java` | 解码 COM_STMT_EXECUTE（最复杂：null-bitmap + 类型 + 二进制参数值） |
| `ComStmtClosePacket.java` | 解码 COM_STMT_CLOSE |
| `StmtPrepareOkPacket.java` | PREPARE 响应（statement_id, num_columns, num_params） |

**`server/auth/MysqlNativePasswordAuth.java`** — mysql_native_password 算法
- 服务端：`verify(clientResponse, challenge, storedPassword)` → boolean
- 客户端：`computeToken(password, challenge)` → byte[]（JDBC 驱动使用）
- `SHA1(password) XOR SHA1(challenge + SHA1(SHA1(password)))` 纯 `java.security.MessageDigest` 实现

**`server/auth/UserManager.java`** — 内存用户存储
- 默认 root 空密码
- `authenticate(username, clientResponse, challenge)` → boolean

### 测试
- `MysqlBufUtilTest.java` — 整数/字符串编解码 round-trip，边界值
- `PacketCodecTest.java` — 各包编码后解码验证
- `MysqlNativePasswordAuthTest.java` — 已知向量验证

---

## Phase 2：Netty 服务器 + 连接生命周期

**目标**：TCP 监听，完成 MySQL 握手，客户端可连接/断开

### 构建变更
`mini-db.gradle` 添加：`implementation 'io.netty:netty-all:4.1.100.Final'`

### 新建文件

**`server/MiniDbServer.java`** — Netty ServerBootstrap
- 构造：port, CatalogSpi, DataSourceSpi, TransactionManager(可null), UserManager
- boss group(1线程) + worker group(CPU核心数) + SQL 执行线程池(独立 ExecutorService，隔离阻塞操作)
- Pipeline：MysqlPacketDecoder → MysqlPacketEncoder → MysqlConnectionHandler

**`server/netty/MysqlPacketDecoder.java`** — ByteToMessageDecoder
- 读 4 字节头（3字节payload长度小端 + 1字节sequence id）
- 等待完整payload，发出 RawMysqlPacket
- 处理超 16MB 包拼接

**`server/netty/MysqlPacketEncoder.java`** — MessageToByteEncoder
- payload 前加 3字节长度 + 1字节 sequence id
- 超 16MB 自动分片

**`server/netty/RawMysqlPacket.java`** — record(sequenceId, ByteBuf payload)

**`server/MysqlConnectionHandler.java`** — ChannelInboundHandlerAdapter
- 状态机：HANDSHAKE → AUTH → COMMAND_PHASE
- channelActive()：发 HandshakePacket
- channelRead()：按状态分发
- Phase 2 仅处理：COM_QUIT, COM_PING, COM_INIT_DB

**`server/ConnectionSession.java`** — 每连接状态
- connectionId, currentDatabase, username, charset, autoCommit
- 持有独立 SqlSession + ExecutionContext（共享 CatalogSpi/DataSourceSpi）
- `Map<Integer, ServerPreparedStatement>` prepared statement 缓存
- PlanCache（每连接独立）

### 测试
- `MiniDbServerTest.java` — 原始 Socket 连接，验证握手包解析、认证流程、COM_QUIT

---

## Phase 3：文本协议（COM_QUERY）

**目标**：mysql CLI 可执行 SQL 查询

### 新建文件

**`server/handler/CommandDispatcher.java`** — 命令路由中心
- 读命令字节 → 分发到对应 Handler

**`server/handler/QueryHandler.java`** — COM_QUERY 处理
- 提交到 SQL 执行线程池（不阻塞 EventLoop）
- 调用 `connectionSession.getSqlSession().execute(sql)`
- 结果判定：检查 `Row` 是否包含 `affected_rows` key
  - 有 → DML → OkPacket(affected_rows)
  - 无 → SELECT → 完整结果集
- DDL（CREATE/DROP/ALTER）→ OkPacket
- 事务控制（BEGIN/COMMIT/ROLLBACK）→ OkPacket
- 异常 → ErrPacket

**`server/handler/ResultSetWriter.java`** — 文本结果集写入
- 列数包 → ColumnDefinitionPacket × N → EOF → ResultSetRowPacket × N → EOF
- 列类型推断：优先从 CatalogSpi 获取 ColumnMeta，回退按 Java Object 类型推断
- 列名处理：`users.id` → table="users", name="id"；`id` → table="", name="id"

**`server/handler/ErrorMapping.java`** — 异常 → MySQL 错误码映射
- 语法错误 → 1064/42000
- 表不存在 → 1146/42S02
- 列不存在 → 1054/42S22
- 通用错误 → 1105/HY000

**`server/handler/StatusFlagBuilder.java`** — ExecutionContext 状态 → MySQL status flags

**`server/handler/SystemVariableHandler.java`** — 拦截 MySQL 客户端特殊查询
- `SELECT @@version_comment` → "mini-db"
- `SELECT @@version` → "8.0.0-minidb"
- `SET NAMES ...` → OK
- `SET autocommit = 0/1` → 委托 ExecutionContext
- `SHOW WARNINGS` → 空结果集
- `SHOW TABLES` → 委托 CatalogSpi.listTables()
- `SELECT DATABASE()` → 当前数据库

### 测试
- `TextProtocolIntegrationTest.java` — 原始 Socket 发送 COM_QUERY 字节流，验证 SELECT/INSERT/UPDATE/DELETE 响应

---

## Phase 4：二进制协议（COM_STMT_PREPARE/EXECUTE/CLOSE）

**目标**：支持 prepared statement 二进制协议

### 新建文件

**`server/handler/ServerPreparedStatement.java`** — 包装 mini-db PreparedStatement
- statementId（服务端分配）
- numParams, paramMeta, resultColumnMeta
- 存储在 ConnectionSession.preparedStatements

**`server/handler/PrepareHandler.java`** — COM_STMT_PREPARE
- 解析 SQL → 创建 mini-db PreparedStatement → 分配 ID
- 响应 StmtPrepareOkPacket + 参数列定义 + 结果列定义

**`server/handler/ExecuteHandler.java`** — COM_STMT_EXECUTE
- 查 ServerPreparedStatement → 解码二进制参数（null bitmap offset=0 + 类型 + 值）
- 调用 `innerPs.reset()` → `setXxx()` → `execute()`
- SELECT → BinaryResultSetWriter; DML → OkPacket

**`server/handler/BinaryResultSetWriter.java`** — 二进制结果集
- 0x00 头 + NULL bitmap(offset=2) + 类型化二进制值
- INT32: 4字节小端; BIGINT: 8字节小端; VARCHAR: length-encoded string; DECIMAL: length-encoded string; DATETIME: length + year(2) + month(1) + day(1) + hour(1) + min(1) + sec(1)

**`server/handler/CloseHandler.java`** — COM_STMT_CLOSE（无响应包）

**`server/handler/ResetHandler.java`** — COM_STMT_RESET

### 测试
- `BinaryProtocolIntegrationTest.java` — 原始 Socket 完整 PREPARE/EXECUTE/CLOSE 流程验证

---

## Phase 5：JDBC 驱动

**目标**：自定义 JDBC 驱动连接 mini-db 服务器

### 包：`cn.zhangyis.minidb.jdbc`

**`jdbc/MysqlClientCodec.java`** — 客户端包 I/O
- 基于 `java.net.Socket` + `InputStream/OutputStream`（不用 Netty）
- `readPacket(InputStream)` → (sequenceId, byte[])
- `writePacket(OutputStream, sequenceId, byte[])`
- 复用与 MysqlBufUtil 相同的编码逻辑（基于 byte[]/ByteBuffer）

**`jdbc/MiniDbDriver.java`** — java.sql.Driver
- URL: `jdbc:minidb://host:port/database`
- static 块注册 DriverManager
- `connect()` → MiniDbConnection

**`jdbc/MiniDbConnection.java`** — java.sql.Connection
- 构造时建立 TCP 连接 + 完成 MySQL 握手认证
- `createStatement()` → MiniDbStatement
- `prepareStatement(sql)` → 发 COM_STMT_PREPARE → MiniDbPreparedStatement
- `setAutoCommit()` → 发 `SET autocommit = 0/1`
- `commit()` / `rollback()` → 发对应 SQL
- `close()` → COM_QUIT + 关闭 Socket

**`jdbc/MiniDbStatement.java`** — java.sql.Statement
- `executeQuery(sql)` → COM_QUERY → 解析结果集 → MiniDbResultSet
- `executeUpdate(sql)` → COM_QUERY → 解析 OkPacket → affected rows

**`jdbc/MiniDbPreparedStatement.java`** — java.sql.PreparedStatement
- `setInt/setLong/setString/setDouble/setNull/setObject` → 存参数
- `executeQuery()` → COM_STMT_EXECUTE(二进制参数) → 解析二进制结果集
- `executeUpdate()` → COM_STMT_EXECUTE → OkPacket
- `close()` → COM_STMT_CLOSE

**`jdbc/MiniDbResultSet.java`** — java.sql.ResultSet
- 内存存储（List of row maps）
- 游标遍历：`next()`, `getInt()`, `getString()`, `getLong()`, `getObject()` 等
- 按索引(1-based)和按名称访问

**`jdbc/MiniDbResultSetMetaData.java`** — java.sql.ResultSetMetaData

**`jdbc/MiniDbDatabaseMetaData.java`** — 最小实现，不支持的方法抛 SQLFeatureNotSupportedException

**`jdbc/MiniDbDataSource.java`** — javax.sql.DataSource 简单连接工厂

**`resources/META-INF/services/java.sql.Driver`** — SPI 注册文件

### 测试
- `MiniDbDriverTest.java` — URL 解析、驱动注册
- `MiniDbStatementTest.java` — 启动嵌入式服务器 → JDBC 连接 → 执行查询验证
- `MiniDbPreparedStatementTest.java` — 参数化查询
- `JdbcTransactionTest.java` — autoCommit/commit/rollback

---

## Phase 6：集成与加固

### 新建文件

**`server/MiniDbServerBuilder.java`** — Builder 模式配置服务器
- `.port(3307)`, `.catalog()`, `.dataSource()`, `.user("root", "")`, `.sqlThreadPoolSize(8)`, `.build()`

**`server/MiniDbServerMain.java`** — `main()` 入口，启动完整服务器
- 对接 `DatabaseBootstrap` 用于真实存储模式

**`server/handler/ConnectionCleanup.java`** — 连接断开清理
- 回滚活跃事务
- 关闭所有 prepared statement
- 释放 ExecutionContext 资源

### 测试
- `FullIntegrationTest.java` — 端到端：启动服务器 → JDBC 连接 → DDL + DML + SELECT + PS + 事务
- `ConcurrentConnectionsTest.java` — 10 并发 JDBC 连接同时执行查询
- `ConnectionDropTest.java` — 客户端异常断开，验证服务端清理

---

## 完整文件清单（约 50 个新文件）

```
server/
├── MiniDbServer.java
├── MiniDbServerBuilder.java
├── MiniDbServerMain.java
├── MysqlConnectionHandler.java
├── ConnectionSession.java
├── netty/
│   ├── MysqlPacketDecoder.java
│   ├── MysqlPacketEncoder.java
│   └── RawMysqlPacket.java
├── protocol/
│   ├── MysqlConstants.java
│   ├── MysqlBufUtil.java
│   ├── TypeMapping.java
│   └── packets/
│       ├── HandshakePacket.java
│       ├── HandshakeResponsePacket.java
│       ├── OkPacket.java
│       ├── ErrPacket.java
│       ├── EofPacket.java
│       ├── ColumnDefinitionPacket.java
│       ├── ResultSetRowPacket.java
│       ├── BinaryResultSetRowPacket.java
│       ├── ComQueryPacket.java
│       ├── ComStmtPreparePacket.java
│       ├── ComStmtExecutePacket.java
│       ├── ComStmtClosePacket.java
│       └── StmtPrepareOkPacket.java
├── auth/
│   ├── MysqlNativePasswordAuth.java
│   └── UserManager.java
└── handler/
    ├── CommandDispatcher.java
    ├── QueryHandler.java
    ├── PrepareHandler.java
    ├── ExecuteHandler.java
    ├── CloseHandler.java
    ├── ResetHandler.java
    ├── InitDbHandler.java
    ├── ResultSetWriter.java
    ├── BinaryResultSetWriter.java
    ├── ErrorMapping.java
    ├── StatusFlagBuilder.java
    ├── SystemVariableHandler.java
    ├── ServerPreparedStatement.java
    └── ConnectionCleanup.java

jdbc/
├── MiniDbDriver.java
├── MiniDbConnection.java
├── MiniDbStatement.java
├── MiniDbPreparedStatement.java
├── MiniDbResultSet.java
├── MiniDbResultSetMetaData.java
├── MiniDbDatabaseMetaData.java
├── MysqlClientCodec.java
└── MiniDbDataSource.java

resources/META-INF/services/java.sql.Driver
```

---

## 实施顺序与依赖

```
Phase 1 (协议编解码) ← 无依赖，纯字节操作
  ↓
Phase 2 (Netty服务器) ← 依赖 Phase 1 包定义，引入 Netty
  ↓
Phase 3 (文本协议) ← 依赖 Phase 2 连接生命周期
  ↓
Phase 4 (二进制协议) ← 依赖 Phase 3 模式（复用 ResultSetWriter 模式）
  ↓ （Phase 5 可与 Phase 4 并行）
Phase 5 (JDBC驱动) ← 依赖 Phase 1 编解码 + Phase 3/4 服务端
  ↓
Phase 6 (集成加固) ← 依赖全部
```

## 验证方式

1. **Phase 1-2**：原始 Socket 字节级测试
2. **Phase 3**：`mysql -h 127.0.0.1 -P 3307 -u root` 命令行连接执行查询
3. **Phase 4-5**：JDBC 代码连接执行参数化查询
4. **Phase 6**：并发压测 + 异常断开恢复
 