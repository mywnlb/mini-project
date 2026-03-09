package cn.zhangyis.minidb.storage.catalog;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.record.logical.DataTuple;
import cn.zhangyis.minidb.storage.record.schema.RecordSchema;
import cn.zhangyis.minidb.storage.transaction.core.Transaction;

import java.util.Iterator;

/**
 * 表操作句柄（供 SQL 层调用）
 *
 * <p>通过 TableDescriptor 桥接 CatalogManager 与底层存储模块（BTree、TransactionalDml 等），
 * 为上层提供简洁的 DML 接口。</p>
 *
 * <h3>Invariants</h3>
 * <ul>
 *   <li>H1: Schema 始终从 SchemaRegistry 获取，不缓存</li>
 *   <li>H2: 生命周期 &le; TableDescriptor 生命周期，表 DROPPED 后不可用</li>
 *   <li>H3: 所有数据操作必须在 Transaction 上下文中</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * TableHandler handler = catalogManager.openTable("db1", "users");
 * Transaction trx = txnManager.begin();
 * try {
 *     handler.insert(trx, tuple, primaryKey);
 *     trx.commit();
 * } catch (Exception e) {
 *     trx.rollback();
 * } finally {
 *     handler.close();
 * }
 * }</pre>
 */
public interface TableHandler extends AutoCloseable {

    /**
     * 获取关联的表描述符
     */
    TableDescriptor getTableDescriptor();

    /**
     * 获取当前 Schema（每次从 SchemaRegistry 取最新版本）
     */
    RecordSchema getCurrentSchema();

    // ==================== DML 操作 ====================

    /**
     * 插入一行
     *
     * @param trx        事务
     * @param tuple      数据元组
     * @param primaryKey 主键字节
     * @return 插入成功返回 true
     */
    boolean insert(Transaction trx, DataTuple tuple, byte[] primaryKey) throws MiniDbException;

    /**
     * 按主键查询一行
     *
     * @param trx        事务
     * @param primaryKey 主键字节
     * @return 数据元组，不存在返回 null
     */
    DataTuple get(Transaction trx, byte[] primaryKey) throws MiniDbException;

    /**
     * 更新一行
     *
     * @param trx        事务
     * @param primaryKey 主键字节
     * @param newTuple   新数据元组
     * @return 更新成功返回 true
     */
    boolean update(Transaction trx, byte[] primaryKey, DataTuple newTuple) throws MiniDbException;

    /**
     * 删除一行
     *
     * @param trx        事务
     * @param primaryKey 主键字节
     * @return 删除成功返回 true
     */
    boolean delete(Transaction trx, byte[] primaryKey) throws MiniDbException;

    // ==================== 扫描操作 ====================

    /**
     * 全表扫描
     *
     * @param trx 事务
     * @return 行迭代器
     */
    Iterator<DataTuple> scan(Transaction trx) throws MiniDbException;

    /**
     * 范围扫描
     *
     * @param trx      事务
     * @param startKey 起始键（含），null 表示从头开始
     * @param endKey   结束键（含），null 表示到末尾
     * @return 行迭代器
     */
    Iterator<DataTuple> rangeScan(Transaction trx, byte[] startKey, byte[] endKey) throws MiniDbException;

    /**
     * 关闭句柄，释放资源
     */
    @Override
    void close();
}
