package cn.zhangyis.mininyadb.backend.dm.logger;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import cn.zhangyis.mininyadb.backend.constants.LogConstants;
import cn.zhangyis.mininyadb.backend.excetions.LogException;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;

public class HeapLoggerImpl implements Logger {

    private File file;
    private RandomAccessFile randomAccessFile;
    private AtomicInteger xCheckSum;
    private volatile int readPosition;
    private ReentrantReadWriteLock lock;

    public HeapLoggerImpl(String path) {
        file = new File(path + LogConstants.LOG_FILE_SUFFIX);
        if (!file.exists()) {
            try {
                file.createNewFile();
                randomAccessFile = new RandomAccessFile(file, "rwd");
                xCheckSum = new AtomicInteger(0);
                updateCheckNum();
            } catch (IOException e) {
                throw new LogException("创建日志文件失败");
            }
        } else {
            try {
                randomAccessFile = new RandomAccessFile(file, "rwd");
                xCheckSum  = new AtomicInteger(getFileCheckSum());
            } catch (FileNotFoundException e) {
                throw new LogException("打开文件失败");
            }
        }
        readPosition = 4;
        lock = new ReentrantReadWriteLock();
    }

    private int getFileCheckSum() {
        try {
            randomAccessFile.getChannel().position(0);
            ByteBuffer buffer = ByteBuffer.allocate(4);
            buffer.flip();
            randomAccessFile.getChannel().read(buffer);
            return buffer.getInt();
        } catch (IOException e) {
            throw new LogException("打开文件失败");
        }
    }

    @Override
    public void appendLog(String log) {
        lock.writeLock().lock();
        try {
            randomAccessFile.seek(randomAccessFile.length());
            byte[] logData = log.getBytes(StandardCharsets.UTF_8);
            int length = logData.length;
            int checkNum = getCheckSum(log);

            byte[] concat = Bytes.concat(Ints.toByteArray(checkNum), Ints.toByteArray(length), logData);
            randomAccessFile.write(concat);

            xCheckSum.addAndGet(checkNum);

            updateCheckNum();
        } catch (Exception e) {
            throw new LogException("写入日志文件失败");
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void updateCheckNum() {
        try {
            randomAccessFile.getChannel().position(0);
            ByteBuffer buffer = ByteBuffer.allocate(4);
            buffer.putInt(xCheckSum.get());
            buffer.flip();
            randomAccessFile.getChannel().write(buffer);
        }catch (Exception e){
            throw new LogException("更新校验和失败");
        }
    }

    private int getCheckSum(String log) {
        return Arrays.hashCode(log.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        try {
            randomAccessFile.close();
        } catch (IOException e) {
            throw new LogException("关闭日志文件失败");
        }
    }

    @Override
    public boolean hasNext() {
        lock.readLock().lock();
        try {
            return readPosition < randomAccessFile.length();
        } catch (IOException e) {
            throw new LogException("检查日志文件位置失败");
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public String next() {
        lock.readLock().lock();
        try {
            if (readPosition >= randomAccessFile.length()) {
                return null;
            }

            randomAccessFile.seek(readPosition);

            ByteBuffer intBuf = ByteBuffer.allocate(Integer.BYTES);

            // 读取日志的校验和
            randomAccessFile.read(intBuf.array());
            int logCheck = intBuf.getInt();
            intBuf.clear();

            // 读取日志的长度
            randomAccessFile.read(intBuf.array());
            int length = intBuf.getInt();
            intBuf.clear();

            // 读取日志内容
            byte[] logData = new byte[length];
            randomAccessFile.readFully(logData);

            readPosition += 2 * Integer.BYTES + length;

            return new String(logData, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new LogException("读取日志文件失败");
        } finally {
            lock.readLock().unlock();
        }
    }
}
