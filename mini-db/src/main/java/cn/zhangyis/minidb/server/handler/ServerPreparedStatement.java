package cn.zhangyis.minidb.server.handler;

import cn.zhangyis.minidb.sql.exec.PreparedStatement;

import java.util.List;

/**
 * 协议层预编译语句包装。
 *
 * <p>将 mini-db 内部的 {@link PreparedStatement} 与协议层元数据关联：
 * 服务端分配的语句 ID、参数数量、结果列元数据等。</p>
 *
 * <p>设计模式：适配器（Adapter）——将内部 API 适配为协议层需要的接口。
 * 内部 PreparedStatement 只关心参数绑定和执行，
 * ServerPreparedStatement 额外维护协议通信所需的元数据。</p>
 */
public class ServerPreparedStatement {

    private final int statementId;
    private final PreparedStatement innerPs;  // null 表示系统变量/拦截类查询
    private final String sql;                 // 原始 SQL（用于拦截类查询的 execute）
    private final int numParams;
    private final List<ResultColumnMetadata> resultColumnMeta;

    public ServerPreparedStatement(int statementId, PreparedStatement innerPs,
                                   int numParams, List<ResultColumnMetadata> resultColumnMeta) {
        this(statementId, innerPs, null, numParams, resultColumnMeta);
    }

    public ServerPreparedStatement(int statementId, String sql,
                                   int numParams, List<ResultColumnMetadata> resultColumnMeta) {
        this(statementId, null, sql, numParams, resultColumnMeta);
    }

    private ServerPreparedStatement(int statementId, PreparedStatement innerPs, String sql,
                                    int numParams, List<ResultColumnMetadata> resultColumnMeta) {
        this.statementId = statementId;
        this.innerPs = innerPs;
        this.sql = sql;
        this.numParams = numParams;
        this.resultColumnMeta = resultColumnMeta;
    }

    public int statementId() { return statementId; }
    public PreparedStatement innerPs() { return innerPs; }
    public String sql() { return sql; }
    public int numParams() { return numParams; }
    public List<ResultColumnMetadata> resultColumnMeta() { return resultColumnMeta; }
    public boolean isIntercepted() { return innerPs == null; }
}
