package cn.zhangyis.minidb.storage;

import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 存储层测试基类
 *
 * <p>提供统一的测试环境初始化和清理逻辑，包括：</p>
 * <ul>
 *   <li>临时测试目录创建</li>
 *   <li>DiskManager 初始化</li>
 *   <li>表空间创建（关键步骤）</li>
 *   <li>BufferPool 初始化</li>
 *   <li>测试后资源清理</li>
 * </ul>
 *
 * <h2>使用方法</h2>
 * <pre>
 * class MyStorageTest extends BaseStorageTest {
 *     &#64;Test
 *     void testSomething() throws Exception {
 *         // 可以直接使用 diskManager, bufferPool, SPACE_ID 等
 *         Page page = bufferPool.newPage(SPACE_ID).getPage();
 *         // ...
 *     }
 *
 *     &#64;Override
 *     protected void afterSetup() throws Exception {
 *         // 可选：子类额外的初始化逻辑
 *     }
 * }
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public abstract class BaseStorageTest {

    /**
     * 默认测试表空间ID
     */
    protected static final int SPACE_ID = 1;

    /**
     * 默认表空间名称
     */
    protected static final String SPACE_NAME = "test_space";

    /**
     * 默认BufferPool大小（64页 = 1MB）
     */
    protected static final int BUFFER_POOL_SIZE = 64;

    /**
     * 临时测试目录
     */
    protected Path testDir;

    /**
     * 磁盘管理器
     */
    protected DiskManager diskManager;

    /**
     * 缓冲池
     */
    protected BufferPool bufferPool;

    /**
     * 测试前初始化
     *
     * <p>执行顺序：</p>
     * <ol>
     *   <li>创建临时测试目录</li>
     *   <li>初始化 DiskManager</li>
     *   <li>创建表空间（关键！）</li>
     *   <li>初始化 BufferPool</li>
     *   <li>调用子类的 afterSetup() 钩子方法</li>
     * </ol>
     *
     * @throws Exception 如果初始化失败
     */
    @BeforeEach
    void setup() throws Exception {
        // 1. 创建临时测试目录
        testDir = Files.createTempDirectory("minidb_test_");

        // 2. 初始化 DiskManager
        diskManager = new DiskManager(testDir);

        // 3. 创建表空间（关键步骤！没有这步会导致页面操作失败）
        diskManager.createTablespace(SPACE_ID, SPACE_NAME);

        // 4. 初始化 BufferPool
        bufferPool = new BufferPool(BUFFER_POOL_SIZE, diskManager);

        // 5. 调用子类的额外初始化（钩子方法）
        afterSetup();
    }

    /**
     * 测试后清理资源
     *
     * <p>执行顺序：</p>
     * <ol>
     *   <li>调用子类的 beforeCleanup() 钩子方法</li>
     *   <li>关闭 BufferPool</li>
     *   <li>关闭 DiskManager</li>
     *   <li>删除临时测试目录及所有文件</li>
     * </ol>
     *
     * @throws Exception 如果清理失败
     */
    @AfterEach
    void cleanup() throws Exception {
        // 1. 调用子类的清理前钩子
        beforeCleanup();

        // 2. 关闭 BufferPool
        if (bufferPool != null) {
            bufferPool.close();
        }

        // 3. 关闭 DiskManager
        if (diskManager != null) {
            diskManager.close();
        }

        // 4. 清理测试文件
        if (testDir != null) {
            Files.walk(testDir)
                    .sorted((a, b) -> -a.compareTo(b))  // 逆序删除（先删文件再删目录）
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            // 忽略删除失败
                        }
                    });
        }
    }

    /**
     * 钩子方法：setup完成后调用
     *
     * <p>子类可以覆盖此方法进行额外的初始化，例如：</p>
     * <ul>
     *   <li>创建额外的表空间</li>
     *   <li>初始化测试数据</li>
     *   <li>创建MiniTransaction</li>
     * </ul>
     *
     * @throws Exception 如果初始化失败
     */
    protected void afterSetup() throws Exception {
        // 默认为空，子类可以覆盖
    }

    /**
     * 钩子方法：cleanup开始前调用
     *
     * <p>子类可以覆盖此方法进行额外的清理，例如：</p>
     * <ul>
     *   <li>提交或回滚事务</li>
     *   <li>关闭额外的资源</li>
     *   <li>验证最终状态</li>
     * </ul>
     *
     * @throws Exception 如果清理失败
     */
    protected void beforeCleanup() throws Exception {
        // 默认为空，子类可以覆盖
    }

    /**
     * 获取 BufferPool 统计信息（便捷方法）
     *
     * @return BufferPool 统计信息
     */
    protected BufferPool.BufferPoolStats getBufferPoolStats() {
        return bufferPool.getStats();
    }

    /**
     * 打印 BufferPool 统计信息（便捷方法）
     */
    protected void printBufferPoolStats() {
        System.out.println("BufferPool Stats: " + getBufferPoolStats());
    }
}
