package cn.zhangyis.sql.validator;

import cn.zhangyis.sql.SQLStatement;
import cn.zhangyis.sql.catalog.CatalogReader;

import java.util.ArrayList;
import java.util.List;

/**
 * @Description TODO
 * @Date 2025/3/13 19:59
 * @Created by libo
 */
public class ValidatorRegistry {
    private final List<SemanticValidator> validators = new ArrayList<>();

    public void register(SemanticValidator validator) {
        validators.add(validator);
    }

    public ValidationResult validateAll(SQLStatement ast, CatalogReader catalog) {
        // 依次执行所有验证器
        ValidationResult result = new ValidationResult();
        for (SemanticValidator validator : validators) {
            ValidationResult r = validator.validate(ast, catalog);
            if (!r.isValid()) {
                result.setValid(false);
                result.getErrors().addAll(r.getErrors());
            }
        }
        return result;
    }
}