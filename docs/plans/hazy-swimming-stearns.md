# MiniDB Transaction Manager + MVCC 实现计划

## 一、概述

为 MiniDB 实现 InnoDB-like 的事务系统，支持 REPEATABLE READ 隔离级别。

**现有基础**:
- 记录格式已预留 TRX_ID(6B)、ROLL_PTR(7B)、ROW_VER(2B)
- MTR 提供页面级原子操作
- Redo Log 已实现（含 Group Commit）
- B+Tree 索引完整可用

---

## 二、新增模块结构

```
cn.zhangyis.minidb.storage.transaction/
├── core/
│   ├── TransactionId.java          # 事务ID (48-bit)
│   ├── TransactionState.java       # 状态枚举
│   ├── Transaction.java            # 事务对象
│   ├── TransactionManager.java     # 事务管理器
│   └── TransactionSysPage.java     # 系统页 (Page 5)
│
├── undo/
│   ├── UndoRecordType.java         # Undo 类型枚举
│   ├── UndoRecord.java             # 基类
│   ├── InsertUndoRecord.java       # INSERT 回滚
│   ├── UpdateUndoRecord.java       # UPDATE 回滚
│   ├── DeleteUndoRecord.java       # DELETE 回滚
│   ├── UndoPage.java               # Undo 页操作
│   ├── UndoSegment.java            # 回滚段
│   └── UndoLogManager.java         # 管理器
│
├── mvcc/
│   ├── ReadView.java               # 读视图
│   ├── VisibilityChecker.java      # 可见性判断
│   └── VersionChainReader.java     # 版本链读取
│
└── pointer/
    └── RollbackPointer.java        # ROLL_PTR 编解码
```

---

## 三、核心数据结构

### 3.1 TRX_ID (6 bytes / 48 bits)
- 全局递增事务序列号
- 持久化到事务系统页

### 3.2 ROLL_PTR (7 bytes / 56 bits)
```
┌────────┬────────────┬───────────────┬────────────┐
│ 1 bit  │  7 bits    │   32 bits     │  16 bits   │
│is_insert│ rseg_id   │   page_no     │  offset    │
└────────┴────────────┴───────────────┴────────────┘
```

### 3.3 Undo Record 类型
- `INSERT (0x0B)`: 存储主键，回滚时 DELETE
- `UPDATE (0x0C)`: 存储旧列值，回滚时还原
- `DELETE (0x0D)`: 存储完整旧行，回滚时清除标记

---

## 四、ReadView 可见性算法

```
isVisible(trx_id):
  1. trx_id == creator_trx_id → 可见
  2. trx_id < up_limit_id → 可见
  3. trx_id >= low_limit_id → 不可见
  4. trx_id in active_list → 不可见
  5. else → 可见
```

不可见时沿 ROLL_PTR 遍历版本链找可见版本。

---

## 五、实现阶段

### Phase 1: 核心数据结构 (1-2 周)
- [ ] TransactionId 类
- [ ] RollbackPointer 类
- [ ] TransactionState 枚举
- [ ] Transaction 类骨架

### Phase 2: Undo Log (2-3 周)
- [ ] UndoRecord 基类及子类
- [ ] UndoPage 布局
- [ ] UndoSegment 管理
- [ ] UndoLogManager

### Phase 3: TransactionManager (2 周)
- [ ] TransactionSysPage
- [ ] begin() / commit() / rollback()
- [ ] 活跃事务列表

### Phase 4: MVCC (2 周)
- [ ] ReadView 实现
- [ ] VersionChainReader
- [ ] REPEATABLE READ 验证

### Phase 5: 集成 (2 周)
- [ ] B+Tree DML 修改
- [ ] Purge 线程
- [ ] 端到端测试

---

## 六、关键修改文件

| 文件 | 修改内容 |
|------|----------|
| `SystemLayout.java` | 已定义偏移，直接使用 |
| `MiniTransaction.java` | DML 操作协调 |
| `RedoLogManager.java` | 事务提交协调 |
| `Segment.java` | Undo 段分配 |
| `BTree*.java` | 添加 TRX_ID/ROLL_PTR 写入 |

---

## 七、核心约束 (Invariants)

| # | 约束 |
|---|------|
| T1 | TRX_ID 全局递增，永不复用 |
| T2 | 事务提交前必须写入 Redo Log |
| T3 | Undo 必须先于数据修改持久化 |
| T4 | ReadView 创建后不可变 |
| T5 | 版本链终止于 INSERT Undo 或 NULL |

---

## 八、禁止设计

| # | 禁止项 | 原因 |
|---|--------|------|
| F1 | TRX_ID 用 32 位 | 溢出风险 |
| F2 | 跳过 Undo 直接修改 | 无法回滚 |
| F3 | MTR 外修改系统列 | 无 Redo 保护 |
| F4 | ReadView 可变 | 破坏 RR 语义 |

---

## 九、验证方式

1. **单元测试**: 每个新类的编解码、状态转换
2. **集成测试**: 事务 begin/commit/rollback 流程
3. **MVCC 测试**: 并发读写可见性验证
4. **压力测试**: 多事务并发，死锁检测
5. **崩溃恢复测试**: 重启后事务状态正确
