package cn.zhangyis.mininyadb.backend.dm.page;

/**
 * @Description TODO
 * @Date 2024/6/26 11:36
 * @Created by libo
 */
public interface Page {
    void lock();
    void unLock();
    void release();
    void setDirty(boolean dirtyFlag);
    boolean isDirty();
    int getPageNumer();
    byte[] getData();
}
