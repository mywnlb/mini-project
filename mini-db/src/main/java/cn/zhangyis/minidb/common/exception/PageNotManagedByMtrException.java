package cn.zhangyis.minidb.common.exception;

import cn.zhangyis.minidb.storage.page.PageId;

/**
 * 页面未被 MTR 管理异常
 *
 * <p>表示尝试标记一个未通过当前 MTR 获取的页面为脏页。</p>
 *
 * <h2>错误码</h2>
 * <ul>
 *   <li>300201 - 页面未在 MTR memo 中</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>
 * if (!memo.contains(pageId)) {
 *     throw PageNotManagedByMtrException.notInMemo(pageId);
 * }
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class PageNotManagedByMtrException extends MtrException {

    /** 错误码：页面未在 MTR memo 中 */
    public static final int ERR_NOT_IN_MEMO = 300201;

    public PageNotManagedByMtrException(String message) {
        super(message);
    }

    public PageNotManagedByMtrException(int errorCode, String message) {
        super(errorCode, message);
    }

    /**
     * 创建页面未在 memo 中异常
     *
     * @param pageId 页面 ID
     * @return PageNotManagedByMtrException
     */
    public static PageNotManagedByMtrException notInMemo(PageId pageId) {
        return new PageNotManagedByMtrException(ERR_NOT_IN_MEMO,
            "Page " + pageId + " is not managed by this MTR. " +
            "You must call getPage() before markDirty().");
    }
}
