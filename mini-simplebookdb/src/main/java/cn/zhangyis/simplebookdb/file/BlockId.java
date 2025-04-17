package cn.zhangyis.simplebookdb.file;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Description 每一个文件的第几块
 * @Date 2024/6/21 16:21
 * @Created by libo
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BlockId {
    /**
     * 文件名称
     */
    private String fileName;
    /**
     * 块编号
     */
    private int blkNum;
}
