package cn.zhangyis.minidb.storage.catalog.ddl;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * DDL Log 重放执行器。
 *
 * <p>replay 判定只依赖 durable catalog snapshot，不依赖 cache 或 tablespace 打开状态。</p>
 */
public class DdlLogReplay {

    private static final Logger log = LoggerFactory.getLogger(DdlLogReplay.class);
    private static final String TABLESPACE_NAME_PREFIX = "table_";

    private final DiskManager diskManager;

    public DdlLogReplay(DiskManager diskManager) {
        this.diskManager = diskManager;
    }

    /**
     * 执行单条 DDL intent。
     *
     * @param record        DDL Log 记录
     * @param liveTableIds  recovery 时从 catalog snapshot 得到的现存 tableId 集合
     * @return true 表示该记录已经处理完成，可以 compact；false 表示应保留等待下次恢复重试
     */
    public boolean replay(DdlLogRecord record, Set<Long> liveTableIds) {
        if (record.replayGuard() == DdlReplayGuard.TABLE_ABSENT && liveTableIds.contains(record.tableId())) {
            log.info("DDL Log replay skipped: tableId={} still exists in catalog, ddlOpId={}",
                    record.tableId(), record.ddlOpId());
            return true;
        }

        return switch (record.logType()) {
            case DELETE_SPACE -> replayDeleteSpace(record);
            default -> {
                log.warn("DDL Log replay ignored unsupported type={} ddlOpId={}",
                        record.logType(), record.ddlOpId());
                yield false;
            }
        };
    }

    private boolean replayDeleteSpace(DdlLogRecord record) {
        int spaceId = record.spaceId();
        String tablespaceName = TABLESPACE_NAME_PREFIX + spaceId;
        try {
            boolean deleted = diskManager.dropTablespaceIfExists(spaceId, tablespaceName);
            if (deleted) {
                log.info("DDL Log replay deleted tablespace: spaceId={}, tableId={}, ddlOpId={}",
                        spaceId, record.tableId(), record.ddlOpId());
            } else {
                log.info("DDL Log replay found no tablespace file: spaceId={}, ddlOpId={}",
                        spaceId, record.ddlOpId());
            }
            return true;
        } catch (MiniDbException e) {
            log.warn("DDL Log replay failed to delete tablespace spaceId={}, ddlOpId={}, reason={}",
                    spaceId, record.ddlOpId(), e.getMessage());
            return false;
        }
    }
}
