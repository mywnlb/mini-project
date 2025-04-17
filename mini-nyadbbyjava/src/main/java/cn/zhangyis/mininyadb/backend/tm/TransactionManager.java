package cn.zhangyis.mininyadb.backend.tm;

/**
 * @Description TODO
 * @Date 2024/6/6 15:13
 * @Created by libo
 */
public interface TransactionManager {
    /**
     * 开启事务
     *
     * @return
     */
    long begin();

    /**
     * 提交事务
     *
     * @param xid
     */
    void commit(long xid);

    /**
     * 回滚事务
     *
     * @param xid
     */
    void abort(long xid);

    /**
     * 判断事务是否处于活动状态
     *
     * @param xid
     * @return
     */
    boolean isActive(long xid);

    /**
     * 判断事务是否已经提交
     *
     * @param xid
     * @return
     */
    boolean isCommitted(long xid);

    /**
     * 判断事务是否已经回滚
     *
     * @param xid
     * @return
     */
    boolean isAborted(long xid);

    /**
     * 关闭事务
     */
    void close();
}
