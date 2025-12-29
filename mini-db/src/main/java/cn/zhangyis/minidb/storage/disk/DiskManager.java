package cn.zhangyis.minidb.storage.disk;

import com.minidb.storage.StorageConstants;
import com.minidb.storage.page.PageId;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 磁盘管理器
 * 
 * <p>负责管理表空间文件 (.ibd 文件) 的创建、打开、读写和关闭。
 * 是存储引擎与操作系统文件系统之间的接口层。</p>
 * 
 * <h2>主要职责</h2>
 * <ul>
 *   <li><b>表空间管理</b>: 创建、打开、关闭表空间文件</li>
 *   <li><b>页面 I/O</b>: 读写 16KB 的页面</li>
 *   <li><b>空间分配</b>: 分配新页面，扩展文件</li>
 *   <li><b>刷盘同步</b>: 确保数据持久化到磁盘</li>
 * </ul>
 * 
 * <h2>文件组织</h2>
 * <pre>
 * dataDir/
 *   ├── table1.ibd     (space_id = 1)
 *   ├── table2.ibd     (space_id = 2)
 *   └── ...
 * </pre>
 * 
 * <h2>并发控制</h2>
 * <p>使用两级锁：
 * <ul>
 *   <li>全局锁 (poolLock): 保护表空间映射的修改</li>
 *   <li>文件锁 (fileLock): 每个表空间文件独立的读写锁</li>
 * </ul>
 * </p>
 * 
 * <h2>InnoDB 对应</h2>
 * <p>对应 InnoDB 的 fil_system 和 os_file 模块。</p>
 * 
 * @author MiniDB
 * @version 1.0
 */
public class DiskManager {
    
    /**
     * 数据目录路径
     * <p>所有表空间文件都存储在此目录下。</p>
     */
    private final Path dataDir;
    
    /**
     * 表空间映射: space_id -> TablespaceFile
     * <p>使用 ConcurrentHashMap 支持并发读取。</p>
     */
    private final Map<Integer, TablespaceFile> tablespaces;
    
    /**
     * 全局读写锁
     * <p>写锁用于添加/删除表空间，读锁用于访问现有表空间。</p>
     */
    private final ReentrantReadWriteLock lock;
    
    // ==================== 构造函数 ====================
    
    /**
     * 创建磁盘管理器
     * 
     * <h3>初始化步骤</h3>
     * <ol>
     *   <li>保存数据目录路径</li>
     *   <li>初始化表空间映射</li>
     *   <li>如果数据目录不存在则创建</li>
     * </ol>
     * 
     * @param dataDir 数据目录路径
     * @throws IOException 如果目录创建失败
     */
    public DiskManager(Path dataDir) throws IOException {
        this.dataDir = dataDir;
        this.tablespaces = new ConcurrentHashMap<>();
        this.lock = new ReentrantReadWriteLock();
        
        // 确保数据目录存在
        if (!Files.exists(dataDir)) {
            Files.createDirectories(dataDir);
        }
    }
    
    // ==================== 表空间管理 ====================
    
    /**
     * 创建新表空间
     * 
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>获取写锁</li>
     *   <li>检查表空间是否已存在</li>
     *   <li>创建 .ibd 文件</li>
     *   <li>初始化第一页 (FSP_HDR)</li>
     *   <li>注册到表空间映射</li>
     * </ol>
     * 
     * @param spaceId 表空间 ID (必须唯一)
     * @param name    表空间名称 (不含 .ibd 后缀)
     * @throws IOException 如果表空间已存在或文件创建失败
     */
    public void createTablespace(int spaceId, String name) throws IOException {
        lock.writeLock().lock();
        try {
            // 检查是否已存在
            if (tablespaces.containsKey(spaceId)) {
                throw new IOException("Tablespace already exists: " + spaceId);
            }
            
            // 创建表空间文件
            Path filePath = dataDir.resolve(name + ".ibd");
            TablespaceFile tsFile = new TablespaceFile(spaceId, filePath, true);
            tablespaces.put(spaceId, tsFile);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 打开已有表空间
     * 
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>获取写锁</li>
     *   <li>检查文件是否存在</li>
     *   <li>打开文件并读取元信息</li>
     *   <li>注册到表空间映射</li>
     * </ol>
     * 
     * @param spaceId 表空间 ID
     * @param name    表空间名称
     * @throws IOException 如果文件不存在
     */
    public void openTablespace(int spaceId, String name) throws IOException {
        lock.writeLock().lock();
        try {
            Path filePath = dataDir.resolve(name + ".ibd");
            if (!Files.exists(filePath)) {
                throw new IOException("Tablespace file not found: " + filePath);
            }
            
            TablespaceFile tsFile = new TablespaceFile(spaceId, filePath, false);
            tablespaces.put(spaceId, tsFile);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 关闭表空间
     * 
     * @param spaceId 表空间 ID
     * @throws IOException 如果关闭失败
     */
    public void closeTablespace(int spaceId) throws IOException {
        lock.writeLock().lock();
        try {
            TablespaceFile tsFile = tablespaces.remove(spaceId);
            if (tsFile != null) {
                tsFile.close();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 检查表空间是否存在
     * 
     * @param spaceId 表空间 ID
     * @return 如果已打开返回 true
     */
    public boolean tablespaceExists(int spaceId) {
        return tablespaces.containsKey(spaceId);
    }
    
    // ==================== 页面 I/O ====================
    
    /**
     * 从磁盘读取页面
     * 
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>获取读锁并找到表空间</li>
     *   <li>计算页面在文件中的偏移</li>
     *   <li>读取 16KB 数据到 ByteBuffer</li>
     *   <li>返回数据</li>
     * </ol>
     * 
     * @param pageId 页面标识
     * @return 包含页面数据的 ByteBuffer (16KB)
     * @throws IOException 如果表空间不存在或读取失败
     */
    public ByteBuffer readPage(PageId pageId) throws IOException {
        lock.readLock().lock();
        try {
            TablespaceFile tsFile = tablespaces.get(pageId.getSpaceId());
            if (tsFile == null) {
                throw new IOException("Tablespace not found: " + pageId.getSpaceId());
            }
            return tsFile.readPage(pageId.getPageNo());
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 将页面写入磁盘
     * 
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>获取读锁并找到表空间</li>
     *   <li>计算页面在文件中的偏移</li>
     *   <li>将 ByteBuffer 中的数据写入文件</li>
     * </ol>
     * 
     * <p><b>注意</b>: 此方法不会自动调用 fsync，需要显式调用 sync() 确保持久化。</p>
     * 
     * @param pageId 页面标识
     * @param data   页面数据 (必须是 16KB)
     * @throws IOException 如果写入失败
     */
    public void writePage(PageId pageId, ByteBuffer data) throws IOException {
        lock.readLock().lock();
        try {
            TablespaceFile tsFile = tablespaces.get(pageId.getSpaceId());
            if (tsFile == null) {
                throw new IOException("Tablespace not found: " + pageId.getSpaceId());
            }
            tsFile.writePage(pageId.getPageNo(), data);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 分配新页面
     * 
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>获取当前页数作为新页号</li>
     *   <li>扩展文件大小 (+16KB)</li>
     *   <li>写入初始化的空页</li>
     *   <li>返回新页号</li>
     * </ol>
     * 
     * @param spaceId 表空间 ID
     * @return 新分配的页号
     * @throws IOException 如果分配失败
     */
    public int allocatePage(int spaceId) throws IOException {
        lock.readLock().lock();
        try {
            TablespaceFile tsFile = tablespaces.get(spaceId);
            if (tsFile == null) {
                throw new IOException("Tablespace not found: " + spaceId);
            }
            return tsFile.allocatePage();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    // ==================== 刷盘和同步 ====================
    
    /**
     * 同步指定表空间到磁盘
     * 
     * <p>调用 fsync 确保所有写入都持久化到物理磁盘。
     * 这是保证 durability 的关键操作。</p>
     * 
     * @param spaceId 表空间 ID
     * @throws IOException 如果同步失败
     */
    public void sync(int spaceId) throws IOException {
        lock.readLock().lock();
        try {
            TablespaceFile tsFile = tablespaces.get(spaceId);
            if (tsFile != null) {
                tsFile.sync();
            }
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 同步所有表空间到磁盘
     * 
     * @throws IOException 如果任一表空间同步失败
     */
    public void syncAll() throws IOException {
        lock.readLock().lock();
        try {
            for (TablespaceFile tsFile : tablespaces.values()) {
                tsFile.sync();
            }
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 关闭磁盘管理器
     * 
     * <p>关闭所有打开的表空间文件，释放资源。</p>
     * 
     * @throws IOException 如果关闭失败
     */
    public void close() throws IOException {
        lock.writeLock().lock();
        try {
            for (TablespaceFile tsFile : tablespaces.values()) {
                tsFile.close();
            }
            tablespaces.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    // ==================== 统计信息 ====================
    
    /**
     * 获取表空间的页面数
     * 
     * @param spaceId 表空间 ID
     * @return 页面数，表空间不存在返回 0
     */
    public int getPageCount(int spaceId) {
        TablespaceFile tsFile = tablespaces.get(spaceId);
        return tsFile != null ? tsFile.getPageCount() : 0;
    }
    
    // ==================== 内部类: 表空间文件 ====================
    
    /**
     * 表空间文件
     * 
     * <p>封装单个 .ibd 文件的操作，包括读写和空间管理。
     * 每个表空间文件有独立的读写锁保护并发访问。</p>
     */
    private static class TablespaceFile {
        
        /** 表空间 ID */
        private final int spaceId;
        
        /** 文件路径 */
        private final Path filePath;
        
        /** 文件通道 (NIO) */
        private final FileChannel channel;
        
        /** 当前页面数 (原子操作) */
        private final AtomicInteger pageCount;
        
        /** 文件级读写锁 */
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
    }
}
