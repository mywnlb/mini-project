package cn.zhangyis.mininyadb.backend.dm.logger;

import java.util.Iterator;

/**
 * @Description TODO
 * @Date 2024/6/19 19:21
 * @Created by libo
 */
public interface Logger extends Iterator<String> {
    public void appendLog(String log);
    public void close();
}
