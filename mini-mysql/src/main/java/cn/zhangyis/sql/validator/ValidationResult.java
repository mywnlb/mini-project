package cn.zhangyis.sql.validator;

import lombok.Data;

import java.util.List;

/**
 * @Description TODO
 * @Date 2025/3/13 19:59
 * @Created by libo
 */
@Data
public class ValidationResult {
    private  boolean valid;
    private List<ValidationError> errors;
}
