package cn.zhangyis.simplebookdb.buffer;

import cn.zhangyis.simplebookdb.exceptions.BufferAbortException;
import cn.zhangyis.simplebookdb.file.BlockId;
import cn.zhangyis.simplebookdb.file.FileMgr;
import cn.zhangyis.simplebookdb.log.LogMgr;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @Description 缓存器管理
 * @Date 2024/7/23 16:32
 * @Created by libo
 */
public class BufferMgr {
    private Buffer[] buffers;
    private int numAvailable;
    private static final long MAX_TIME = 5; // 10 seconds
    private ReentrantLock lock = new ReentrantLock();
    private Condition condition = lock.newCondition();

    public BufferMgr(FileMgr fileMgr, LogMgr logMgr, int numBuffs) {
        buffers = new Buffer[numBuffs];
        numAvailable = numBuffs;
        for (int i = 0; i < numBuffs; i++) {
            buffers[i] = new Buffer(fileMgr, logMgr);
        }
    }

    public int available() {
        return numAvailable;
    }

    /**
     * 某事物的数据全部刷新
     *
     * @param txnum
     */
    public synchronized void flushAll(int txnum) {
        for (Buffer buffer : buffers) {
            if (buffer.modifyingTx() == txnum) {
                buffer.flush();
            }
        }
    }

    /**
     * 解除指定数据缓冲区的引脚。如果其引脚计数
     * 归零，则通知所有等待的线程。
     *
     * @param buffer
     */
    public void unpin(Buffer buffer) {
        lock.lock();
        try {
            buffer.unpin();
            if (!buffer.isPinned()) {
                numAvailable++;
                condition.signalAll();
            }
        } finally {
            lock.unlock();
        }

    }

    public void pin(BlockId blk) {
        try {
            if (lock.tryLock(5, TimeUnit.SECONDS)) {
                try {
                    // 获得锁后执行的代码
                    //循环查找是否已经存在
                    Buffer buffer = findExistingBuffer(blk);
                    if (buffer == null) {
                        //尝试查找没有被应用
                        buffer = chooseUnPinBuffer();

                        if (buffer == null) {
                            throw new BufferAbortException("内存不足未获取buffer");
                        }

                        buffer.assignToBlock(blk);
                    }

                    //如果选中的是一个没有被引用的buffer
                    if(!buffer.isPinned()){
                        numAvailable--;
                    }
                    buffer.pin();
                } finally {
                    lock.unlock();
                }
            } else {
                // 未能在指定时间内获得锁的处理
                throw new BufferAbortException("超时未能获取锁");

            }
        } catch (InterruptedException e) {
            // 处理线程被中断的情况
            throw new BufferAbortException("业务异常");
        }
    }


    private Buffer chooseUnPinBuffer() {
        for (Buffer buffer : buffers) {
            if (!buffer.isPinned()) {
                return buffer;
            }
        }
        return null;
    }

    /**
     * 循环查找已经存在的buff是否相等
     *
     * @param blk
     * @return
     */
    private Buffer findExistingBuffer(BlockId blk) {
        for (Buffer buffer : buffers) {
            if (Objects.equals(buffer.block(), blk)) {
                return buffer;
            }
        }

        return null;
    }
}
