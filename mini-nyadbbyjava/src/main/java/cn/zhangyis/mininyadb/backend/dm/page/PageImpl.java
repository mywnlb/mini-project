package cn.zhangyis.mininyadb.backend.dm.page;

import java.util.concurrent.locks.Lock;

/**
 * @Description TODO
 * @Date 2024/6/26 11:45
 * @Created by libo
 */
public class PageImpl implements Page{
    private int pageNumber;
    private Lock lock;
    private boolean dirtyFlag;

    @Override
    public void lock() {

    }

    @Override
    public void unLock() {

    }

    @Override
    public void release() {

    }

    @Override
    public void setDirty(boolean dirtyFlag) {

    }

    @Override
    public boolean isDirty() {
        return false;
    }

    @Override
    public int getPageNumer() {
        return 0;
    }

    @Override
    public byte[] getData() {
        return new byte[0];
    }
}
