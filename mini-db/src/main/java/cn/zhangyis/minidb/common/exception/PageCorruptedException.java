package cn.zhangyis.minidb.common.exception;

import cn.zhangyis.minidb.storage.page.PageId;

/**
 * 页面损坏异常
 *
 * <p>表示页面数据损坏，如校验和不匹配、格式错误、魔数错误等。</p>
 *
 * <h2>错误码</h2>
 * <ul>
 *   <li>100201 - 校验和不匹配</li>
 *   <li>100202 - 页面格式错误</li>
 *   <li>100203 - 魔数错误</li>
 *   <li>100204 - 页面类型不匹配</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>
 * if (!page.verifyChecksum()) {
 *     throw PageCorruptedException.checksumMismatch(pageId,
 *         storedChecksum, calculatedChecksum);
 * }
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class PageCorruptedException extends StorageException {

    /** 错误码：校验和不匹配 */
    public static final int ERR_CHECKSUM_MISMATCH = 100201;

    /** 错误码：页面格式错误 */
    public static final int ERR_INVALID_FORMAT = 100202;

    /** 错误码：魔数错误 */
    public static final int ERR_INVALID_MAGIC = 100203;

    /** 错误码：页面类型不匹配 */
    public static final int ERR_TYPE_MISMATCH = 100204;

    public PageCorruptedException(String message) {
        super(message);
    }

    public PageCorruptedException(int errorCode, String message) {
        super(errorCode, message);
    }

    public PageCorruptedException(String message, Throwable cause) {
        super(message, cause);
    }

    public PageCorruptedException(int errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    /**
     * 创建校验和不匹配异常
     *
     * @param pageId     页面 ID
     * @param stored     存储的校验和
     * @param calculated 计算的校验和
     * @return PageCorruptedException
     */
    public static PageCorruptedException checksumMismatch(
            PageId pageId, int stored, int calculated) {
        return new PageCorruptedException(ERR_CHECKSUM_MISMATCH,
            String.format("Checksum mismatch on page %s: stored=0x%08x, calculated=0x%08x",
                pageId, stored, calculated));
    }

    /**
     * 创建页面格式错误异常
     *
     * @param pageId 页面 ID
     * @param reason 原因
     * @return PageCorruptedException
     */
    public static PageCorruptedException invalidFormat(PageId pageId, String reason) {
        return new PageCorruptedException(ERR_INVALID_FORMAT,
            "Invalid page format on " + pageId + ": " + reason);
    }

    /**
     * 创建魔数错误异常
     *
     * @param pageId       页面 ID
     * @param expectedMagic 期望的魔数
     * @param actualMagic   实际的魔数
     * @return PageCorruptedException
     */
    public static PageCorruptedException invalidMagic(
            PageId pageId, int expectedMagic, int actualMagic) {
        return new PageCorruptedException(ERR_INVALID_MAGIC,
            String.format("Invalid magic number on page %s: expected=0x%08x, actual=0x%08x",
                pageId, expectedMagic, actualMagic));
    }
}
