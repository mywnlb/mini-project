package cn.zhangyis.mininyadb.backend.dm.page;

/**
 * @Description TODO
 * @Date 2024/6/26 11:51
 * @Created by libo
 */
public class PageCacheImpl implements PageCache{

    @Override
    public int newPage(byte[] data) {
        return 0;
    }

    @Override
    public Page getPage(int pageNumber) {
        return null;
    }

    @Override
    public void close() {

    }

    @Override
    public void truncateByPgno(int maxPgno) {

    }

    @Override
    public int getNowPages() {
        return 0;
    }

    @Override
    public void flushPage(int pageNumber) {

    }
}
