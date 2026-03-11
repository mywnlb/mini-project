package cn.zhangyis.minidb.sql.exec;

/**
 * 火山模型执行器接口
 * open() → next() → close()
 */
public interface ExecNode {
    void open();
    Row next();  // 返回 null 表示结束
    void close();
}
