package cn.zhangyis.simplebookdb.tx.concurrency;

import cn.zhangyis.simplebookdb.file.BlockId;

import java.util.HashMap;
import java.util.Map;

/**
 * @Description 锁定文件快
 * @Date 2024/7/24 17:19
 * @Created by libo
 */
public class LockTable {
    private static final long MAX_TIME = 10000; // 10 seconds

    private Map<BlockId,Integer> locks = new HashMap<BlockId,Integer>();

    public synchronized void sLock(BlockId blk){

    }
}
