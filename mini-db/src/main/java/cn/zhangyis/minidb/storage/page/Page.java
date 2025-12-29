package cn.zhangyis.minidb.storage.page;

import com.minidb.storage.StorageConstants;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/**
 * InnoDB 页面基类
 * 
 * <p>所有 InnoDB 页面的基础类，封装了 16KB 页面的通用结构和操作。
 * 页面是数据库 I/O 的基本单位，所有数据都以页为单位进行读写。</p>
 * 
 * <h2>页面物理布局 (16KB = 16384 字节)</h2>
 * <pre>
 * +-------------------------------------------+  Offset 0
 * |             FIL Header (38 bytes)         |
 * |  +-------------------------------------+  |
 * |  | checksum      (4)  - 页面校验和     |  |
 * |  | page_no       (4)  - 页号           |  |
 * |  | prev_page     (4)  - 前一页号       |  |
 * |  | next_page     (4)  - 后一页号       |  |
 * |  | lsn           (8)  - 最后修改LSN    |  |
 * |  | page_type     (2)  - 页类型         |  |
 * |  | flush_lsn     (8)  - 刷盘LSN        |  |
 * |  | space_id      (4)  - 表空间ID       |  |
 * |  +-------------------------------------+  |
 * +-------------------------------------------+  Offset 38
 * |                                           |
 * |          Page Body (16298 bytes)          |
 * |    (具体结构由页类型决定，如 IndexPage)    |
 * |                                           |
 * +-------------------------------------------+  Offset 16376
 * |             FIL Trailer (8 bytes)         |
 * |  +-------------------------------------+  |
 * |  | checksum      (4)  - 校验和副本     |  |
 * |  | lsn_low32     (4)  - LSN低32位      |  |
 * |  +-------------------------------------+  |
 * +-------------------------------------------+  Offset 16384
 * </pre>
 * 
 * <h2>字节序</h2>
 * <p>InnoDB 使用 Little Endian (小端序) 存储多字节整数。
 * 本实现通过 {@link ByteOrder#LITTLE_ENDIAN} 保持兼容。</p>
 * 
 * <h2>校验和</h2>
 * <p>使用 CRC32 算法计算页面校验和，用于检测数据损坏。
 * Header 和 Trailer 中都存储校验和，用于检测 partial write。</p>
 * 
 * <h2>InnoDB 源码参考</h2>
 * <ul>
 *   <li>fil0fil.h - FIL Header/Trailer 定义</li>
 *   <li>buf0buf.h - Buffer Pool 中的页面操作</li>
 * </ul>
 * 
 * @author MiniDB
 * @version 1.0
 * @see IndexPage
 * @see PageType
 */
public class Page {
    
    // ==================== FIL Header 字段偏移量 ====================
    
    /** 校验和偏移 (4 bytes) - 用于数据完整性校验 */
    public static final int FIL_PAGE_SPACE_OR_CHKSUM = 0;
    
    /** 页号偏移 (4 bytes) - 页面在表空间中的序号 */
    public static final int FIL_PAGE_OFFSET = 4;
    
    /** 前一页偏移 (4 bytes) - 双向链表的前指针 */
    public static final int FIL_PAGE_PREV = 8;
    
    /** 后一页偏移 (4 bytes) - 双向链表的后指针 */
    public static final int FIL_PAGE_NEXT = 12;
    
    /** LSN 偏移 (8 bytes) - Log Sequence Number，最后修改的日志序列号 */
    public static final int FIL_PAGE_LSN = 16;
    
    /** 页类型偏移 (2 bytes) - 标识页面用途 */
    public static final int FIL_PAGE_TYPE = 24;
    
    /** 刷盘 LSN 偏移 (8 bytes) - 仅系统表空间第一页使用 */
    public static final int FIL_PAGE_FILE_FLUSH_LSN = 26;
    
    /** 表空间 ID 偏移 (4 bytes) - 页面所属的表空间 */
    public static final int FIL_PAGE_SPACE_ID = 34;
    
    // ==================== 实例字段 ====================
    
    /**
     * 页面数据缓冲区
     * 
     * <p>使用 ByteBuffer 存储页面的原始字节数据。
     * 设置为 Little Endian 字节序以兼容 InnoDB。</p>
     */
    protected final ByteBuffer buffer;
    
    /**
     * 页面标识
     * 
     * <p>包含 space_id 和 page_no，唯一标识此页面。</p>
     */
    protected final PageId pageId;
    
    /**
     * 脏页标记
     * 
     * <p>如果页面在内存中被修改但尚未刷盘，则为 true。
     * 脏页需要在某个时刻写回磁盘以保证持久性。</p>
     */
    protected boolean dirty;
    
    // ==================== 构造函数 ====================
    
    /**
     * 创建新的空白页面
     * 
     * <p>分配 16KB 内存并初始化 FIL Header。
     * 适用于新分配页面的场景。</p>
     * 
     * <h3>初始化步骤</h3>
     * <ol>
     *   <li>分配 16KB 的 ByteBuffer</li>
     *   <li>设置字节序为 Little Endian</li>
     *   <li>清零所有字节</li>
     *   <li>设置 page_no 和 space_id</li>
     *   <li>将 prev/next 设为 FIL_NULL</li>
     * </ol>
     * 
     * @param pageId 页面标识
     */
    public Page(PageId pageId) {
        this.pageId = pageId;
        this.buffer = ByteBuffer.allocate(StorageConstants.PAGE_SIZE);
        this.buffer.order(ByteOrder.LITTLE_ENDIAN);
        this.dirty = false;
        initializePage();
    }
    
    /**
     * 从已有数据创建页面
     * 
     * <p>用于从磁盘读取页面后，在内存中重建 Page 对象。
     * 会复制传入的数据到内部缓冲区。</p>
     * 
     * <h3>处理步骤</h3>
     * <ol>
     *   <li>分配新的 ByteBuffer</li>
     *   <li>设置字节序</li>
     *   <li>复制传入的数据</li>
     *   <li>标记为非脏页（刚从磁盘读取）</li>
     * </ol>
     * 
     * @param pageId 页面标识
     * @param data   从磁盘读取的原始数据
     */
    public Page(PageId pageId, ByteBuffer data) {
        this.pageId = pageId;
        this.buffer = ByteBuffer.allocate(StorageConstants.PAGE_SIZE);
        this.buffer.order(ByteOrder.LITTLE_ENDIAN);
        
        // 复制数据
        data.rewind();
        this.buffer.put(data);
        this.buffer.rewind();
        
        this.dirty = false;
    }
    
    /**
     * 初始化新页面
     * 
     * <p>设置新页面的默认值，包括清零、设置页号和链表指针。</p>
     */
    protected void initializePage() {
        // 步骤1: 清零整个页面
        for (int i = 0; i < StorageConstants.PAGE_SIZE; i++) {
            buffer.put(i, (byte) 0);
        }
        
        // 步骤2: 设置基本 FIL Header 字段
        setPageNo(pageId.getPageNo());
        setSpaceId(pageId.getSpaceId());
        
        // 步骤3: 初始化链表指针为 FIL_NULL（表示无前后页）
        setPrevPage(StorageConstants.FIL_NULL);
        setNextPage(StorageConstants.FIL_NULL);
        
        // 步骤4: 设置默认页类型
        setPageType(PageType.FIL_PAGE_TYPE_ALLOCATED);
    }
    
    // ==================== FIL Header Getters/Setters ====================
    
    /**
     * 获取页面校验和
     * 
     * @return 存储在页面中的 CRC32 校验和
     */
    public int getChecksum() {
        return buffer.getInt(FIL_PAGE_SPACE_OR_CHKSUM);
    }
    
    /**
     * 设置页面校验和
     * 
     * @param checksum CRC32 校验和
     */
    public void setChecksum(int checksum) {
        buffer.putInt(FIL_PAGE_SPACE_OR_CHKSUM, checksum);
    }
    
    /**
     * 获取页号
     * 
     * @return 页面在表空间中的序号
     */
    public int getPageNo() {
        return buffer.getInt(FIL_PAGE_OFFSET);
    }
    
    /**
     * 设置页号
     * 
     * @param pageNo 页面序号
     */
    public void setPageNo(int pageNo) {
        buffer.putInt(FIL_PAGE_OFFSET, pageNo);
        markDirty();
    }
    
    /**
     * 获取前一页号
     * 
     * <p>用于叶子页的双向链表。FIL_NULL 表示没有前一页。</p>
     * 
     * @return 前一页的页号，或 FIL_NULL
     */
    public int getPrevPage() {
        return buffer.getInt(FIL_PAGE_PREV);
    }
    
    /**
     * 设置前一页号
     * 
     * @param prev 前一页的页号，或 FIL_NULL
     */
    public void setPrevPage(int prev) {
        buffer.putInt(FIL_PAGE_PREV, prev);
        markDirty();
    }
    
    /**
     * 获取后一页号
     * 
     * @return 后一页的页号，或 FIL_NULL
     */
    public int getNextPage() {
        return buffer.getInt(FIL_PAGE_NEXT);
    }
    
    /**
     * 设置后一页号
     * 
     * @param next 后一页的页号，或 FIL_NULL
     */
    public void setNextPage(int next) {
        buffer.putInt(FIL_PAGE_NEXT, next);
        markDirty();
    }
    
    /**
     * 获取 LSN (Log Sequence Number)
     * 
     * <p>LSN 是最后一次修改此页面的日志序列号，用于：
     * <ul>
     *   <li>崩溃恢复时判断页面是否需要重做</li>
     *   <li>Checkpoint 时确定脏页刷盘顺序</li>
     * </ul>
     * </p>
     * 
     * @return 最后修改的 LSN
     */
    public long getLsn() {
        return buffer.getLong(FIL_PAGE_LSN);
    }
    
    /**
     * 设置 LSN
     * 
     * <p>同时更新 Header 中的 LSN 和 Trailer 中的 LSN 低 32 位。</p>
     * 
     * @param lsn 日志序列号
     */
    public void setLsn(long lsn) {
        // 设置 Header 中的 LSN
        buffer.putLong(FIL_PAGE_LSN, lsn);
        // 设置 Trailer 中的 LSN 低 32 位 (用于校验)
        buffer.putInt(StorageConstants.PAGE_SIZE - 4, (int) lsn);
        markDirty();
    }
    
    /**
     * 获取页面类型
     * 
     * @return 页面类型枚举值
     */
    public PageType getPageType() {
        return PageType.fromValue(buffer.getShort(FIL_PAGE_TYPE) & 0xFFFF);
    }
    
    /**
     * 设置页面类型
     * 
     * @param type 页面类型
     */
    public void setPageType(PageType type) {
        buffer.putShort(FIL_PAGE_TYPE, (short) type.getValue());
        markDirty();
    }
    
    /**
     * 获取表空间 ID
     * 
     * @return 页面所属的表空间 ID
     */
    public int getSpaceId() {
        return buffer.getInt(FIL_PAGE_SPACE_ID);
    }
    
    /**
     * 设置表空间 ID
     * 
     * @param spaceId 表空间 ID
     */
    public void setSpaceId(int spaceId) {
        buffer.putInt(FIL_PAGE_SPACE_ID, spaceId);
        markDirty();
    }
    
    // ==================== 通用方法 ====================
    
    /**
     * 获取页面标识
     * 
     * @return PageId 对象
     */
    public PageId getPageId() {
        return pageId;
    }
    
    /**
     * 获取页面数据的 ByteBuffer 副本
     * 
     * <p>返回一个新的 ByteBuffer，指向相同的数据但有独立的位置指针。
     * 用于将数据写入磁盘。</p>
     * 
     * @return ByteBuffer 副本 (Little Endian)
     */
    public ByteBuffer getBuffer() {
        ByteBuffer dup = buffer.duplicate();
        dup.order(ByteOrder.LITTLE_ENDIAN);
        dup.rewind();
        return dup;
    }
    
    /**
     * 获取页面数据的字节数组副本
     * 
     * @return 16KB 的字节数组
     */
    public byte[] getBytes() {
        byte[] data = new byte[StorageConstants.PAGE_SIZE];
        buffer.position(0);
        buffer.get(data);
        buffer.rewind();
        return data;
    }
    
    /**
     * 检查是否为脏页
     * 
     * @return 如果页面被修改但未刷盘返回 true
     */
    public boolean isDirty() {
        return dirty;
    }
    
    /**
     * 标记页面为脏
     * 
     * <p>任何修改页面内容的操作都应该调用此方法。</p>
     */
    public void markDirty() {
        this.dirty = true;
    }
    
    /**
     * 清除脏页标记
     * 
     * <p>在页面成功刷盘后调用。</p>
     */
    public void clearDirty() {
        this.dirty = false;
    }
    
    // ==================== 校验和相关 ====================
    
    /**
     * 计算页面校验和
     * 
     * <p>使用 CRC32 算法计算页面的校验和。
     * 计算范围：跳过 Header 中的 checksum 字段和 Trailer。</p>
     * 
     * <h3>计算范围</h3>
     * <pre>
     * [0-3]     : checksum (跳过)
     * [4-16375] : 参与计算
     * [16376-16383] : trailer (跳过)
     * </pre>
     * 
     * @return CRC32 校验和
     */
    public int calculateChecksum() {
        CRC32 crc = new CRC32();
        byte[] data = getBytes();
        // 跳过前 4 字节 (checksum) 和后 8 字节 (trailer)
        crc.update(data, 4, StorageConstants.PAGE_SIZE - 4 - StorageConstants.FIL_TRAILER_SIZE);
        return (int) crc.getValue();
    }
    
    /**
     * 准备刷盘前的处理
     * 
     * <p>在将页面写入磁盘前必须调用此方法，它会：
     * <ol>
     *   <li>计算页面校验和</li>
     *   <li>将校验和写入 Header</li>
     *   <li>将校验和写入 Trailer</li>
     * </ol>
     * </p>
     */
    public void prepareForFlush() {
        int checksum = calculateChecksum();
        // 设置 Header 中的校验和
        setChecksum(checksum);
        // 设置 Trailer 中的校验和
        buffer.putInt(StorageConstants.PAGE_SIZE - 8, checksum);
    }
    
    /**
     * 验证页面完整性
     * 
     * <p>比较存储的校验和与重新计算的校验和。
     * 如果不匹配，说明页面数据已损坏或写入不完整。</p>
     * 
     * @return 如果校验和匹配返回 true
     */
    public boolean verifyChecksum() {
        int stored = getChecksum();
        int calculated = calculateChecksum();
        // 0 表示校验和未设置 (新页面)，也视为有效
        return stored == calculated || stored == 0;
    }
    
    // ==================== 原始字节读写方法 ====================
    
    /**
     * 读取指定偏移的单个字节
     * 
     * @param offset 页内偏移 (0-16383)
     * @return 字节值
     */
    public byte getByte(int offset) {
        return buffer.get(offset);
    }
    
    /**
     * 写入单个字节到指定偏移
     * 
     * @param offset 页内偏移
     * @param value  字节值
     */
    public void putByte(int offset, byte value) {
        buffer.put(offset, value);
        markDirty();
    }
    
    /**
     * 读取指定偏移的 short (2字节)
     * 
     * @param offset 页内偏移
     * @return short 值 (Little Endian)
     */
    public short getShort(int offset) {
        return buffer.getShort(offset);
    }
    
    /**
     * 写入 short 到指定偏移
     * 
     * @param offset 页内偏移
     * @param value  short 值
     */
    public void putShort(int offset, short value) {
        buffer.putShort(offset, value);
        markDirty();
    }
    
    /**
     * 读取指定偏移的 int (4字节)
     * 
     * @param offset 页内偏移
     * @return int 值 (Little Endian)
     */
    public int getInt(int offset) {
        return buffer.getInt(offset);
    }
    
    /**
     * 写入 int 到指定偏移
     * 
     * @param offset 页内偏移
     * @param value  int 值
     */
    public void putInt(int offset, int value) {
        buffer.putInt(offset, value);
        markDirty();
    }
    
    /**
     * 读取指定偏移的 long (8字节)
     * 
     * @param offset 页内偏移
     * @return long 值 (Little Endian)
     */
    public long getLong(int offset) {
        return buffer.getLong(offset);
    }
    
    /**
     * 写入 long 到指定偏移
     * 
     * @param offset 页内偏移
     * @param value  long 值
     */
    public void putLong(int offset, long value) {
        buffer.putLong(offset, value);
        markDirty();
    }
    
    /**
     * 读取指定偏移的字节数组
     * 
     * @param offset 页内偏移
     * @param dst    目标数组
     */
    public void getBytes(int offset, byte[] dst) {
        buffer.position(offset);
        buffer.get(dst);
        buffer.rewind();
    }
    
    /**
     * 写入字节数组到指定偏移
     * 
     * @param offset 页内偏移
     * @param src    源数组
     */
    public void putBytes(int offset, byte[] src) {
        buffer.position(offset);
        buffer.put(src);
        buffer.rewind();
        markDirty();
    }
    
    /**
     * 返回页面的字符串表示
     * 
     * @return 包含页面关键信息的字符串
     */
    @Override
    public String toString() {
        return String.format("Page{id=%s, type=%s, lsn=%d, dirty=%s}",
            pageId, getPageType(), getLsn(), dirty);
    }
}
