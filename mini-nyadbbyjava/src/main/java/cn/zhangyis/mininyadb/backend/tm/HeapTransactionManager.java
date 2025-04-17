package cn.zhangyis.mininyadb.backend.tm;

import cn.hutool.core.lang.Assert;
import cn.zhangyis.mininyadb.backend.constants.TmConstants;
import cn.zhangyis.mininyadb.backend.excetions.TmException;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class HeapTransactionManager implements TransactionManager {
    private File file;
    private int xidCounter;
    private ReentrantReadWriteLock lock;
    private RandomAccessFile raf;

    public HeapTransactionManager(String path) {
        this.file = new File(path + TmConstants.SUFFIX_XID);
        if (!this.file.exists()) {
            try {
                this.file.createNewFile();
                raf = new RandomAccessFile(this.file, "rw");
                raf.getChannel().write(ByteBuffer.allocate(TmConstants._XID_FILE_HEADER_SIZE));
                raf.getChannel().force(true);
            } catch (IOException e) {
                throw new TmException("事务日志文件创建失败");
            }
        } else {
            try {
                raf = new RandomAccessFile(this.file, "rw");
            } catch (IOException e) {
                throw new TmException("事务日志文件打开失败");
            }
        }

        Assert.isTrue(this.file.canRead(), "事务日志文件不能读");
        Assert.isTrue(this.file.canWrite(), "事务日志文件不能写");

        this.lock = new ReentrantReadWriteLock();
        checkXIDCounter();
    }

    private void checkXIDCounter() {
        ByteBuffer buf = ByteBuffer.allocate(TmConstants._XID_FILE_HEADER_SIZE);
        try {
            raf.getChannel().position(0);
            raf.getChannel().read(buf);
        } catch (IOException e) {
            throw new TmException("事务日志文件读取失败");
        }
        buf.flip();
        this.xidCounter = buf.getInt();
    }

    private long getXidPosition(long position) {
        return TmConstants._XID_FILE_HEADER_SIZE + (position - 1) * TmConstants._XID_FIELD_SIZE;
    }

    @Override
    public long begin() {
        lock.writeLock().lock();
        try {
            xidCounter++;
            updateXID(xidCounter, TmConstants._FIELD_TRAN_ACTIVE);
            incXidCount();
            return xidCounter;
        } catch (IOException e) {
            throw new TmException("事务日志文件写入失败");
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void incXidCount() throws IOException {
        raf.getChannel().position(0);
        ByteBuffer allocate = ByteBuffer.allocate(TmConstants._XID_FILE_HEADER_SIZE);
        allocate.putInt(xidCounter);
        allocate.flip();
        raf.getChannel().write(allocate);
        raf.getChannel().force(true);
    }

    private void updateXID(long xid, byte status) {
        try {
            raf.getChannel().position(getXidPosition(xid));
            ByteBuffer wrap = ByteBuffer.allocate(1);
            wrap.put(status);
            wrap.flip();
            raf.getChannel().write(wrap);
            raf.getChannel().force(true);
        } catch (IOException e) {
            throw new TmException("事务日志文件写入失败");
        }
    }

    @Override
    public void commit(long xid) {
        updateXID(xid, TmConstants._FIELD_TRAN_COMMITED);
    }

    @Override
    public void abort(long xid) {
        updateXID(xid, TmConstants._FIELD_TRAN_ABORTED);
    }

    @Override
    public boolean isActive(long xid) {
        return checkXidStatus(xid, TmConstants._FIELD_TRAN_ACTIVE);
    }

    private boolean checkXidStatus(long xid, byte status) {
        ByteBuffer buf = ByteBuffer.allocate(TmConstants._XID_FIELD_SIZE);
        try {
            raf.getChannel().position(getXidPosition(xid));
            raf.getChannel().read(buf);
            buf.flip();
        } catch (IOException e) {
            throw new TmException("事务日志文件读取失败");
        }
        return buf.get() == status;
    }

    @Override
    public boolean isCommitted(long xid) {
        return checkXidStatus(xid, TmConstants._FIELD_TRAN_COMMITED);
    }

    @Override
    public boolean isAborted(long xid) {
        return checkXidStatus(xid, TmConstants._FIELD_TRAN_ABORTED);
    }

    @Override
    public void close() {
        try {
            raf.close();
        } catch (IOException e) {
            throw new TmException("事务日志文件关闭失败");
        }
    }
}
