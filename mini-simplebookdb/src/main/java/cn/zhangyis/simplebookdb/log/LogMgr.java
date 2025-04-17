package cn.zhangyis.simplebookdb.log;

import cn.zhangyis.simplebookdb.file.BlockId;
import cn.zhangyis.simplebookdb.file.FileMgr;
import cn.zhangyis.simplebookdb.file.Page;

import java.util.Iterator;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 日志管理器，负责
 * 将日志记录写入日志文件。日志尾部
 * 日志尾部保存在字节缓冲区中，需要时将其刷新到磁盘上。
 * 在需要时刷新到磁盘。
 *
 * @Date 2024/7/8 15:35
 * @Created by libo
 */
public class LogMgr {
    private FileMgr fileMgr;
    private String logFile;
    private Page logPage;
    private BlockId currnetBlk;
    private int latestLSN = 0;
    private int latestSaveLSN = 0;
    private ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public LogMgr(FileMgr fileMgr, String logFile) {
        this.fileMgr = fileMgr;
        this.logFile = logFile;

        byte[] datas = new byte[fileMgr.getBlockSize()];
        logPage = new Page(datas);

        //判断文件是否存在
        int fileBlk = this.fileMgr.getFileLength(logFile);

        if (fileBlk == 0) {
            currnetBlk = appendNewBlock();

        } else {
            currnetBlk = new BlockId(logFile, fileBlk);

            this.fileMgr.read(currnetBlk, logPage);
        }

    }

    private BlockId appendNewBlock() {
        BlockId append = fileMgr.append(logFile);
        logPage.setInt(0, fileMgr.getBlockSize());
        fileMgr.write(append, logPage);
        return append;
    }

    /**
     * 确保之前的lsn被保存
     *
     * @param lsn
     */
    public void flush(int lsn) {
        if (lsn >= latestSaveLSN) {
            flush();
        }
    }

    private void flush() {
        fileMgr.write(currnetBlk, logPage);
        latestSaveLSN = latestLSN;
    }

    /**
     * 向日志缓冲区添加日志记录。
     * 记录由任意字节数组组成。
     * 日志记录在缓冲区中从右到左写入。
     * 记录的大小写在字节之前。
     * 缓冲区的开头包含最后写入的记录的位置。
     * 最后写入的记录的位置（"边界"）。
     * 反向存储记录便于按相反顺序读取。
     * 以相反的顺序读取。
     * 返回最终值的 LSN
     *
     * @param datas
     */
    public int append(byte[] datas) {
        lock.writeLock().lock();
        try {
            //上一次的边界
            int bundry = logPage.getInt(0);

            //如果当前页不够则新加一页，需要首先刷新
            int length = datas.length;
            if (bundry -1 < length) {
                flush();
                currnetBlk = appendNewBlock();
                bundry = logPage.getInt(0);
            }

            //倒序插入
            int recpos = bundry - length;
            logPage.setBytes(recpos,datas);
            logPage.setInt(0,recpos);

        } finally {
            lock.writeLock().unlock();
        }

        return 0;
    }

    public Iterator<byte[]> iterator(){
        flush();
        return new LogIterator(fileMgr,currnetBlk);
    }
}
