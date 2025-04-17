package cn.zhangyis.mininyadb.backend.dm.page;

public interface PageCache {
    int newPage(byte[] data);
    Page getPage(int pageNumber);
    void close();

    void truncateByPgno(int maxPgno);
    int getNowPages();
    void flushPage(int pageNumber);
}
