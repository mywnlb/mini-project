package cn.zhangyis.sql.validator;

import cn.zhangyis.sql.SQLStatement;
import cn.zhangyis.sql.catalog.CatalogReader;

/**
 * @Description sql语义校验器
 * @Date 2025/3/13 19:59
 * @Created by libo
 */
public interface SemanticValidator {
    // 执行验证并收集错误
    ValidationResult validate(SQLStatement ast, CatalogReader catalog);
}