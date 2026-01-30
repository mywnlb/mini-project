package cn.zhangyis.minidb.storage.disk;

import cn.zhangyis.minidb.common.exception.DiskIOException;
import cn.zhangyis.minidb.common.exception.StorageException;
import cn.zhangyis.minidb.storage.page.PageId;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li><b>页面 I/O</b>: 读写 16KB 的页面 (带重试机制)</li>
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
 * <h2>I/O 重试机制</h2>
 * <p>所有 I/O 操作 (readPage, writePage, allocatePage, sync) 都自动应用重试策略：</p>
 * <ul>
 *   <li>默认最多重试 3 次</li>
 *   <li>指数退避 + 抖动 (100ms ~ 5s)</li>
 *   <li>仅瞬态错误重试 (永久错误如文件不存在立即失败)</li>
 *   <li>总超时 30 秒</li>
 * </ul>
 *
 * <h2>InnoDB 对应</h2>
 * <p>对应 InnoDB 的 fil_system 和 os_file 模块。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class DiskManager implements AutoCloseable {

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

    /**
     * I/O 操作重试策略
     * <p>用于处理瞬态磁盘 I/O 错误，提升生产环境稳定性。</p>
     */
    private final RetryPolicy retryPolicy;

    // ==================== 构造函数 ====================

    public DiskManager(String string) throws DiskIOException {
        this(Path.of(string));

    }

    /**
     * 创建磁盘管理器 (使用默认重试策略)
     *
     * <h3>初始化步骤</h3>
     * <ol>
     *   <li>保存数据目录路径</li>
     *   <li>初始化表空间映射</li>
     *   <li>初始化默认重试策略</li>
     *   <li>如果数据目录不存在则创建</li>
     * </ol>
     *
     * @param dataDir 数据目录路径
     * @throws DiskIOException 如果目录创建失败
     */
    public DiskManager(Path dataDir) throws DiskIOException {
        this(dataDir, RetryPolicy.defaultPolicy());
    }

    /**
     * 创建磁盘管理器 (使用自定义重试策略)
     *
     * <p>允许配置自定义的 I/O 重试行为，适用于特殊环境 (如网络存储、云盘)。</p>
     *
     * @param dataDir     数据目录路径
     * @param retryPolicy I/O 重试策略
     * @throws DiskIOException 如果目录创建失败
     */
    public DiskManager(Path dataDir, RetryPolicy retryPolicy) throws DiskIOException {
        this.dataDir = dataDir;
        this.tablespaces = new ConcurrentHashMap<>();
        this.lock = new ReentrantReadWriteLock();
        this.retryPolicy = retryPolicy;

        // 确保数据目录存在
        try {
            if (!Files.exists(dataDir)) {
                Files.createDirectories(dataDir);
            }
        } catch (IOException e) {
            throw new DiskIOException(DiskIOException.ERR_FILE_CREATE,
                "Failed to create data directory: " + dataDir, e);
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
     * @throws StorageException 如果表空间已存在或文件创建失败
     */
    public void createTablespace(int spaceId, String name) throws StorageException {
        lock.writeLock().lock();
        try {
            // 检查是否已存在
            if (tablespaces.containsKey(spaceId)) {
                throw new StorageException(
                    "Tablespace already exists: " + spaceId);
            }

            // 创建表空间文件
            Path filePath = dataDir.resolve(name + ".ibd");
            TablespaceFile tsFile = new TablespaceFile(spaceId, filePath, true);
            tablespaces.put(spaceId, tsFile);
        } catch (IOException e) {
            throw DiskIOException.fileOpenFailed(name + ".ibd", e);
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
     * @throws DiskIOException 如果文件不存在
     */
    public void openTablespace(int spaceId, String name) throws DiskIOException {
        lock.writeLock().lock();
        try {
            Path filePath = dataDir.resolve(name + ".ibd");
            if (!Files.exists(filePath)) {
                throw new DiskIOException(DiskIOException.ERR_FILE_OPEN,
                    "Tablespace file not found: " + filePath);
            }

            TablespaceFile tsFile = new TablespaceFile(spaceId, filePath, false);
            tablespaces.put(spaceId, tsFile);
        } catch (IOException e) {
            throw DiskIOException.fileOpenFailed(name + ".ibd", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 关闭表空间
     *
     * @param spaceId 表空间 ID
     * @throws DiskIOException 如果关闭失败
     */
    public void closeTablespace(int spaceId) throws DiskIOException {
        lock.writeLock().lock();
        try {
            TablespaceFile tsFile = tablespaces.remove(spaceId);
            if (tsFile != null) {
                tsFile.close();
            }
        } catch (IOException e) {
            throw new DiskIOException(DiskIOException.ERR_FILE_SYNC,
                "Failed to close tablespace: " + spaceId, e);
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
     *   <li>释放锁</li>
     *   <li>在锁外执行 I/O 操作 (带重试)</li>
     *   <li>返回数据</li>
     * </ol>
     *
     * <p><b>重试行为</b>: 瞬态 I/O 错误 (如网络存储抖动) 会自动重试，最多 3 次。</p>
     *
     * @param pageId 页面标识
     * @return 包含页面数据的 ByteBuffer (16KB)
     * @throws DiskIOException 如果表空间不存在或读取失败 (重试后)
     */
    public ByteBuffer readPage(PageId pageId) throws DiskIOException {
        // 获取表空间文件引用（短暂持锁）
        TablespaceFile tsFile;
        lock.readLock().lock();
        try {
            tsFile = tablespaces.get(pageId.getSpaceId());
            if (tsFile == null) {
                throw new DiskIOException(DiskIOException.ERR_FILE_OPEN,
                    "Tablespace not found: " + pageId.getSpaceId());
            }
        } finally {
            lock.readLock().unlock();
        }

        // 在锁外执行耗时的 I/O 操作，使用重试机制
        return RetryHelper.executeWithRetry(
            () -> tsFile.readPage(pageId.getPageNo()),
            retryPolicy,
            "readPage",
            pageId
        );
    }

    /**
     * 将页面写入磁盘
     *
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>获取读锁并找到表空间</li>
     *   <li>释放锁</li>
     *   <li>在锁外执行 I/O 操作 (带重试)</li>
     * </ol>
     *
     * <p><b>注意</b>: 此方法不会自动调用 fsync，需要显式调用 sync() 确保持久化。</p>
     *
     * <p><b>重试行为</b>: 瞬态写入错误 (如磁盘忙) 会自动重试，最多 3 次。</p>
     *
     * @param pageId 页面标识
     * @param data   页面数据 (必须是 16KB)
     * @throws DiskIOException 如果写入失败 (重试后)
     */
    public void writePage(PageId pageId, ByteBuffer data) throws DiskIOException {
        // 获取表空间文件引用（短暂持锁）
        TablespaceFile tsFile;
        lock.readLock().lock();
        try {
            tsFile = tablespaces.get(pageId.getSpaceId());
            if (tsFile == null) {
                throw new DiskIOException(DiskIOException.ERR_FILE_OPEN,
                    "Tablespace not found: " + pageId.getSpaceId());
            }
        } finally {
            lock.readLock().unlock();
        }

        // 在锁外执行耗时的 I/O 操作，使用重试机制
        RetryHelper.executeWithRetry(
            () -> {
                tsFile.writePage(pageId.getPageNo(), data);
                return null;
            },
            retryPolicy,
            "writePage",
            pageId
        );
    }

    /**
     * 分配新页面
     *
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>获取表空间文件引用</li>
     *   <li>释放锁</li>
     *   <li>在锁外分配页面（扩展文件 +16KB，带重试）</li>
     *   <li>返回新页号</li>
     * </ol>
     *
     * <p><b>重试行为</b>: 文件扩展失败 (如磁盘空间暂时不足) 会自动重试。</p>
     *
     * @param spaceId 表空间 ID
     * @return 新分配的页号
     * @throws DiskIOException 如果分配失败 (重试后)
     */
    public int allocatePage(int spaceId) throws DiskIOException {
        // 获取表空间文件引用（短暂持锁）
        TablespaceFile tsFile;
        lock.readLock().lock();
        try {
            tsFile = tablespaces.get(spaceId);
            if (tsFile == null) {
                throw new DiskIOException(DiskIOException.ERR_FILE_OPEN,
                    "Tablespace not found: " + spaceId);
            }
        } finally {
            lock.readLock().unlock();
        }

        // 在锁外执行耗时的分配操作，使用重试机制
        return RetryHelper.executeWithRetry(
            tsFile::allocatePage,
            retryPolicy,
            "allocatePage",
            "spaceId=" + spaceId
        );
    }

    // ==================== 刷盘和同步 ====================

    /**
     * 同步指定表空间到磁盘
     *
     * <p>调用 fsync 确保所有写入都持久化到物理磁盘。
     * 这是保证 durability 的关键操作。</p>
     *
     * <p><b>重试行为</b>: fsync 失败 (如 I/O 繁忙) 会自动重试，最多 3 次。</p>
     *
     * @param spaceId 表空间 ID
     * @throws DiskIOException 如果同步失败 (重试后)
     */
    public void sync(int spaceId) throws DiskIOException {
        // 获取表空间文件引用（短暂持锁）
        TablespaceFile tsFile;
        lock.readLock().lock();
        try {
            tsFile = tablespaces.get(spaceId);
            if (tsFile == null) {
                return; // 表空间不存在，直接返回
            }
        } finally {
            lock.readLock().unlock();
        }

        // 在锁外执行耗时的 fsync 操作，使用重试机制
        RetryHelper.executeWithRetry(
            () -> {
                tsFile.sync();
                return null;
            },
            retryPolicy,
            "sync",
            "spaceId=" + spaceId
        );
    }

    /**
     * 同步所有表空间到磁盘
     *
     * <p>先获取所有表空间文件的快照，然后在锁外执行 fsync。</p>
     *
     * <p><b>重试行为</b>: 每个表空间的 fsync 独立重试，一个失败不影响其他表空间。</p>
     *
     * @throws DiskIOException 如果任一表空间同步失败 (重试后)
     */
    public void syncAll() throws DiskIOException {
        // 获取所有表空间文件的快照（短暂持锁）
        TablespaceFile[] snapshot;
        lock.readLock().lock();
        try {
            snapshot = tablespaces.values().toArray(new TablespaceFile[0]);
        } finally {
            lock.readLock().unlock();
        }

        // 在锁外执行耗时的 fsync 操作，每个表空间独立重试
        for (TablespaceFile tsFile : snapshot) {
            RetryHelper.executeWithRetry(
                () -> {
                    tsFile.sync();
                    return null;
                },
                retryPolicy,
                "syncAll",
                "spaceId=" + tsFile.getSpaceId()
            );
        }
    }

    /**
     * 关闭磁盘管理器
     *
     * <p>关闭所有打开的表空间文件，释放资源。</p>
     *
     * @throws DiskIOException 如果关闭失败
     */
    public void close() throws DiskIOException {
        lock.writeLock().lock();
        try {
            for (TablespaceFile tsFile : tablespaces.values()) {
                tsFile.close();
            }
            tablespaces.clear();
        } catch (IOException e) {
            throw new DiskIOException(DiskIOException.ERR_FILE_SYNC,
                "Failed to close disk manager", e);
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

}
