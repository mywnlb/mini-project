package cn.zhangyis.storage.catalog;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Description TODO
 * @Date 2025/5/28 14:18
 * @Created by libo
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Column {

    private String name;
    private String type;
}
