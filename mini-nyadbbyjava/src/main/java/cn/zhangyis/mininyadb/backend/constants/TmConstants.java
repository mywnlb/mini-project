package cn.zhangyis.mininyadb.backend.constants;

public interface TmConstants {
    //事物日志文件头部长度
    Integer _XID_FILE_HEADER_SIZE = 8;
    //每个事务在xid文件中使用字节长度
    Integer _XID_FIELD_SIZE = 1;     // 每个事务在xid文件中使用字节长度
    //事务三种状态 0:活动 1:提交 2:回滚
    byte _FIELD_TRAN_ACTIVE = 0; // 事务三种状态
    byte _FIELD_TRAN_COMMITED = 1;
    byte _FIELD_TRAN_ABORTED = 2;
    //事物文件后缀
    String SUFFIX_XID = ".xid";
}
