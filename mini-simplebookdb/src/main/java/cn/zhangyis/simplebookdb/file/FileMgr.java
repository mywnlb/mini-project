package cn.zhangyis.simplebookdb.file;

import cn.zhangyis.simplebookdb.exceptions.FileException;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;


/**
 * @Description 文件管理器
 * @Date 2024/6/21 16:22
 * @Created by libo
 */
public class FileMgr {
    /**
     * 数据库文件目录
     */
    private String dbDirectory;

    /**
     * 每一个段的大小
     */
    private int blockSize;

    /**
     * 已经存在的文件集合
     */
    private Map<String, RandomAccessFile> openFiles = new ConcurrentHashMap<>();

    private Map<String, ReentrantReadWriteLock> lockMap = new ConcurrentHashMap<>();

    public FileMgr(String dbDirectory, int blockSize) {
        this.dbDirectory = dbDirectory;
        this.blockSize = blockSize;
        File file = new File(dbDirectory);
        if (!file.exists()) {
            file.mkdirs();
        }

    }

    /**
     * 读取某个文件的某一块进入分页
     *
     * @param blockId
     * @param page
     */
    public void read(BlockId blockId, Page page) {
        //获取文件
        RandomAccessFile randomAccessFile = getFile(blockId.getFileName());
        ReentrantReadWriteLock reentrantReadWriteLock = lockMap.get(blockId.getFileName());
        reentrantReadWriteLock.readLock().lock();
        try {
            randomAccessFile.seek(getPagePosition(blockId.getBlkNum()));
            randomAccessFile.getChannel().read(page.contents());
        } catch (IOException e) {
            throw new FileException("打开文件异常");
        } finally {
            reentrantReadWriteLock.readLock().unlock();
        }
    }

    /**
     * 蒋某个页写入文件
     *
     * @param blockId
     * @param page
     */
    public void write(BlockId blockId, Page page) {
        //获取文件
        RandomAccessFile randomAccessFile = getFile(blockId.getFileName());
        ReentrantReadWriteLock reentrantReadWriteLock = lockMap.get(blockId.getFileName());
        reentrantReadWriteLock.writeLock().lock();
        try {
            randomAccessFile.seek(getPagePosition(blockId.getBlkNum()));
            randomAccessFile.getChannel().write(page.contents());
        } catch (IOException e) {
            throw new FileException("打开文件异常");
        } finally {
            reentrantReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * 为某个文件新增一页
     *
     * @param fileName
     * @return
     */
    public BlockId append(String fileName) {
        int newBlockNum = getFileLength(fileName);
        BlockId blockId = new BlockId(fileName, newBlockNum);
        RandomAccessFile randomAccessFile = getFile(blockId.getFileName());
        ReentrantReadWriteLock reentrantReadWriteLock = lockMap.get(blockId.getFileName());
        reentrantReadWriteLock.writeLock().lock();
        try {
            randomAccessFile.seek(getPagePosition(blockId.getBlkNum()));
            randomAccessFile.write(new byte[blockSize]);
        } catch (IOException e) {
            throw new FileException("打开文件异常");
        } finally {
            reentrantReadWriteLock.writeLock().unlock();
        }
        return blockId;
    }

    public int getFileLength(String fileName) {
        try {
            RandomAccessFile file = getFile(fileName);
            return (int) file.length() / blockSize;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * 获取某个快的开始位置
     *
     * @param blkNum
     * @return
     */
    public int getPagePosition(int blkNum) {
        return blkNum * blockSize;
    }

    /**
     * 获取某个数据库的文件
     *
     * @param fileName
     * @return
     */
    private RandomAccessFile getFile(String fileName) {
        RandomAccessFile randomAccessFile = openFiles.get(fileName);
        if (Objects.isNull(randomAccessFile)) {
            File dbTable = new File(dbDirectory, fileName);
            try {
                randomAccessFile = new RandomAccessFile(dbTable, "rws");
                openFiles.put(fileName, randomAccessFile);
            } catch (Exception e) {
                throw new FileException("文件新建异常");
            }

            //给每一个文件一个锁
            lockMap.put(fileName, new ReentrantReadWriteLock());
        }

        return randomAccessFile;
    }

    public int getBlockSize() {
        return blockSize;
    }
}
