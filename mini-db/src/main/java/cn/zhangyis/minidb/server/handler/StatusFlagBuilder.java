package cn.zhangyis.minidb.server.handler;

import cn.zhangyis.minidb.server.protocol.MysqlConstants;
import cn.zhangyis.minidb.sql.exec.ExecutionContext;

/**
 * 根据 ExecutionContext 状态构建 MySQL 状态标志。
 *
 * <p>OK/EOF 包中的 status_flags 字段告知客户端服务端当前状态：
 * 是否在事务中、是否处于自动提交模式等。
 * 客户端据此决定 UI 提示和后续行为。</p>
 */
public final class StatusFlagBuilder {

    private StatusFlagBuilder() {}

    /**
     * 从 ExecutionContext 构建状态标志。
     */
    public static int build(ExecutionContext ctx) {
        int flags = 0;
        if (ctx.inTransaction()) {
            flags |= MysqlConstants.SERVER_STATUS_IN_TRANS;
        } else {
            // 不在显式事务中 → 自动提交模式
            flags |= MysqlConstants.SERVER_STATUS_AUTOCOMMIT;
        }
        return flags;
    }

    /**
     * 构建默认自动提交状态标志。
     */
    public static int autoCommit() {
        return MysqlConstants.SERVER_STATUS_AUTOCOMMIT;
    }
}
