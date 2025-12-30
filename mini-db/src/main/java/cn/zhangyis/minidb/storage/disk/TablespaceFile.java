package cn.zhangyis.minidb.storage.disk;

/**
 * @Description TODO
 * @Date 2025/12/29 23:43
 * @Created by libo
 */

import cn.zhangyis.minidb.storage.constants.StorageConstants;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 表空间文件
 *
 * <p>封装单个 .ibd 文件的操作，包括读写和空间管理。
 * 每个表空间文件有独立的读写锁保护并发访问。</p>
 */
public class TablespaceFile {

    /**
     * 表空间 ID
     */
    private final int spaceId;

    /**
     * 文件路径
     */
    private final Path filePath;

    /**
     * 文件通道 (NIO)
     */
    private final FileChannel channel;

    /**
     * 当前页面数 (原子操作)
     */
    private final AtomicInteger pageCount;

    /**
     * 文件级读写锁
     */
    private final ReentrantReadWriteLock fileLock;

    /**
     * 创建或打开表空间文件
     *
     * @param spaceId  表空间 ID
     * @param filePath 文件路径
     * @param create   true=创建新文件, false=打开已有文件
     * @throws IOException 如果操作失败
     */
    TablespaceFile(int spaceId, Path filePath, boolean create) throws IOException {
        this.spaceId = spaceId;
        this.filePath = filePath;
        this.fileLock = new ReentrantReadWriteLock();

        if (create) {
            // 创建新文件
            this.channel = FileChannel.open(filePath,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            this.pageCount = new AtomicInteger(0);

            // 初始化第一页
            initializeFirstPage();
        } else {
            // 打开已有文件
            this.channel = FileChannel.open(filePath,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE);

            // 根据文件大小计算页数
            long fileSize = channel.size();
            this.pageCount = new AtomicInteger((int) (fileSize / StorageConstants.PAGE_SIZE));
        }
    }

    /**
     * 初始化表空间的第一页 (FSP_HDR)
     *
     * <p>第一页包含表空间的元信息，如空间ID等。</p>
     */
    void initializeFirstPage() throws IOException {
        fileLock.writeLock().lock();
        try {
            ByteBuffer page = ByteBuffer.allocate(StorageConstants.PAGE_SIZE);

            // 设置基本 FIL Header 字段
            page.putInt(4, 0);                          // page_no = 0
            page.putInt(8, StorageConstants.FIL_NULL);  // prev = FIL_NULL
            page.putInt(12, StorageConstants.FIL_NULL); // next = FIL_NULL
            page.putInt(34, spaceId);                   // space_id

            page.rewind();
            channel.write(page, 0);
            channel.force(true);  // 确保持久化
            pageCount.set(1);
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    /**
     * 读取指定页面
     *
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>验证页号有效性</li>
     *   <li>计算文件偏移: pageNo * PAGE_SIZE</li>
     *   <li>读取 16KB 数据</li>
     *   <li>处理可能的部分读取 (循环直到读满)</li>
     * </ol>
     *
     * @param pageNo 页号
     * @return 页面数据
     * @throws IOException 如果读取失败
     */
    ByteBuffer readPage(int pageNo) throws IOException {
        fileLock.readLock().lock();
        try {
            // 验证页号
            if (pageNo < 0 || pageNo >= pageCount.get()) {
                throw new IOException("Invalid page number: " + pageNo
                        + ", total pages: " + pageCount.get());
            }

            // 分配缓冲区
            ByteBuffer buf = ByteBuffer.allocate(StorageConstants.PAGE_SIZE);

            // 计算偏移并读取
            long offset = (long) pageNo * StorageConstants.PAGE_SIZE;

            // 循环读取直到填满 (处理部分读取)
            int bytesRead = 0;
            while (bytesRead < StorageConstants.PAGE_SIZE) {
                int r = channel.read(buf, offset + bytesRead);
                if (r == -1) {
                    throw new IOException("Unexpected EOF reading page: " + pageNo);
                }
                bytesRead += r;
            }

            buf.flip();
            return buf;
        } finally {
            fileLock.readLock().unlock();
        }
    }

    /**
     * 写入页面到指定位置
     *
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>验证页号</li>
     *   <li>计算文件偏移</li>
     *   <li>写入数据 (循环直到写完)</li>
     * </ol>
     *
     * @param pageNo 页号
     * @param data   页面数据
     * @throws IOException 如果写入失败
     */
    void writePage(int pageNo, ByteBuffer data) throws IOException {
        fileLock.writeLock().lock();
        try {
            if (pageNo < 0) {
                throw new IOException("Invalid page number: " + pageNo);
            }

            long offset = (long) pageNo * StorageConstants.PAGE_SIZE;
            data.rewind();

            // 循环写入直到完成
            int bytesWritten = 0;
            while (bytesWritten < StorageConstants.PAGE_SIZE) {
                bytesWritten += channel.write(data, offset + bytesWritten);
            }
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    /**
     * 分配新页面
     *
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>获取当前页数作为新页号</li>
     *   <li>原子递增页数</li>
     *   <li>扩展文件并写入空页</li>
     * </ol>
     *
     * @return 新页号
     * @throws IOException 如果分配失败
     */
    int allocatePage() throws IOException {
        fileLock.writeLock().lock();
        try {
            int newPageNo = pageCount.getAndIncrement();

            // 创建并初始化空页
            ByteBuffer emptyPage = ByteBuffer.allocate(StorageConstants.PAGE_SIZE);

            // 设置基本 FIL Header
            emptyPage.putInt(4, newPageNo);                 // page_no
            emptyPage.putInt(8, StorageConstants.FIL_NULL); // prev
            emptyPage.putInt(12, StorageConstants.FIL_NULL);// next
            emptyPage.putInt(34, spaceId);                  // space_id

            // 写入文件
            emptyPage.rewind();
            long offset = (long) newPageNo * StorageConstants.PAGE_SIZE;
            channel.write(emptyPage, offset);

            return newPageNo;
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    /**
     * 同步到磁盘 (fsync)
     *
     * @throws IOException 如果同步失败
     */
    void sync() throws IOException {
        channel.force(true);
    }

    /**
     * 关闭文件
     *
     * @throws IOException 如果关闭失败
     */
    void close() throws IOException {
        sync();  // 关闭前确保数据持久化
        channel.close();
    }

    /**
     * 获取页面数
     *
     * @return 当前页面数
     */
    int getPageCount() {
        return pageCount.get();
    }

    /**
     * 获取表空间 ID
     *
     * @return 表空间 ID
     */
    int getSpaceId() {
        return spaceId;
    }
}
