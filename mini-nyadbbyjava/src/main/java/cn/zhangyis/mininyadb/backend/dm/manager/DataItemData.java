package cn.zhangyis.mininyadb.backend.dm.manager;

import lombok.Data;

/**
 * @Description TODO
 * @Date 2024/6/26 11:30
 * @Created by libo
 */
@Data
public class DataItemData {
    private int uuid;
    /**
     * 校验标志位 0合法 1非法
     */
    private byte validFlag;
    /**
     * 数据长度
     */
    private long dataSize;
    /**
     * 数据
     */
    private byte[] data;
}
