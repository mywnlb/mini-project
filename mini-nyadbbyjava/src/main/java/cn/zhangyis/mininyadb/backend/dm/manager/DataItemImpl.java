package cn.zhangyis.mininyadb.backend.dm.manager;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * @Description TODO
 * @Date 2024/6/26 11:32
 * @Created by libo
 */
public class DataItemImpl implements DataItem{
    private DataItemData raw;
    private DataItemData oldRaw;
    private ReentrantReadWriteLock lock;

    @Override
    public void before() {

    }

    @Override
    public void unBefore() {

    }

    @Override
    public void after(int uuid) {

    }

    @Override
    public void release() {

    }
}
