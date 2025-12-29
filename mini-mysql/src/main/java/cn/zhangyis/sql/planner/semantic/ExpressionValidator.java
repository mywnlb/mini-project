//package cn.zhangyis.sql.planner.semantic;
//
//
//
//import cn.zhangyis.sql.parser.expression.BinaryExpression;
//import cn.zhangyis.sql.parser.expression.Expression;
//import cn.zhangyis.sql.planner.semantic.scope.SqlValidatorScope;
//import cn.zhangyis.storage.catalog.CatalogManager;
//
//import java.util.*;
//
//
///**
// * 表达式验证器
// * 负责验证SQL表达式的语义正确性
// */
//public class ExpressionValidator {
//    private final CatalogManager catalogManager;
//    private final SqlValidatorScope scope;
//
//    public ExpressionValidator(CatalogManager catalogManager) {
//        this(catalogManager, new SqlValidatorScope());
//    }
//
//    public ExpressionValidator(CatalogManager catalogManager, SqlValidatorScope scope) {
//        this.catalogManager = catalogManager;
//        this.scope = scope;
//    }
//
//    /**
//     * 验证表达式
//     */
//    public void validate(Expression expr) throws SemanticException {
//        if (expr instanceof ColumnReference) {
//            validateColumnReference((ColumnReference) expr);
//        } else if (expr instanceof Literal) {
//            validateLiteral((Literal) expr);
//        } else if (expr instanceof BinaryExpression) {
//            validateBinaryExpression((BinaryExpression) expr);
//        } else if (expr instanceof FunctionCall) {
//            validateFunctionCall((FunctionCall) expr);
//        } else if (expr instanceof Subquery) {
//            validateSubquery((Subquery) expr);
//        } else {
//            throw new SemanticException("Unsupported expression type: " + expr.getClass().getName());
//        }
//    }
//
//    /**
//     * 验证列引用
//     */
//    private void validateColumnReference(ColumnReference column) throws SemanticException {
//        String tableName = column.getTableName();
//        String columnName = column.getColumnName();
//
//        // 查找列
//        Column col = scope.findColumn(tableName, columnName);
//        if (col == null) {
//            throw new SemanticException("Column not found: " +
//                (tableName != null ? tableName + "." : "") + columnName);
//        }
//
//        // 设置列的类型
//        column.setType(col.getType());
//    }
//
//    /**
//     * 验证字面量
//     */
//    private void validateLiteral(Literal literal) throws SemanticException {
//        String type = literal.getType();
//        Object value = literal.getValue();
//
//        // 验证字面量的类型和值
//        validateLiteralValue(type, value);
//
//        // 设置字面量的类型
//        literal.setType(type);
//    }
//
//    /**
//     * 验证字面量的值
//     */
//    private void validateLiteralValue(String type, Object value) throws SemanticException {
//        if (value == null) {
//            return; // NULL值可以用于任何类型
//        }
//
//        try {
//            switch (type) {
//                case "INT":
//                    Integer.parseInt(value.toString());
//                    break;
//                case "BIGINT":
//                    Long.parseLong(value.toString());
//                    break;
//                case "FLOAT":
//                case "DOUBLE":
//                    Double.parseDouble(value.toString());
//                    break;
//                case "BOOLEAN":
//                    Boolean.parseBoolean(value.toString());
//                    break;
//                case "DATE":
//                case "TIME":
//                case "DATETIME":
//                case "TIMESTAMP":
//                    // 日期时间类型的验证在TypeSystem中处理
//                    break;
//                default:
//                    if (!TypeSystem.isStringType(type)) {
//                        throw new SemanticException("Unsupported literal type: " + type);
//                    }
//            }
//        } catch (NumberFormatException e) {
//            throw new SemanticException("Invalid value for type " + type + ": " + value);
//        }
//    }
//
//    /**
//     * 验证二元表达式
//     */
//    private void validateBinaryExpression(BinaryExpression expr) throws SemanticException {
//        // 验证左操作数
//        validate(expr.getLeft());
//
//        // 验证右操作数
//        validate(expr.getRight());
//
//        // 推导表达式的类型
//        String resultType = TypeSystem.deriveBinaryExpressionType(
//            expr.getOperator(),
//            expr.getLeft().getType(),
//            expr.getRight().getType()
//        );
//
//        // 设置表达式的类型
//        expr.setType(resultType);
//    }
//
//    /**
//     * 验证函数调用
//     */
//    private void validateFunctionCall(FunctionCall func) throws SemanticException {
//        // 验证函数参数
//        List<String> argTypes = new ArrayList<>();
//        for (Expression arg : func.getArguments()) {
//            validate(arg);
//            argTypes.add(arg.getType());
//        }
//
//        // 推导函数的返回类型
//        String returnType = TypeSystem.deriveFunctionType(func.getFunctionName(), argTypes);
//
//        // 设置函数的返回类型
//        func.setType(returnType);
//    }
//
//    /**
//     * 验证子查询
//     */
//    private void validateSubquery(Subquery subquery) throws SemanticException {
//        // 创建子查询验证器
//        SubqueryValidator validator = new SubqueryValidator(catalogManager, scope);
//
//        // 验证子查询
//        validator.validate(subquery);
//
//        // 设置子查询的类型
//        if (subquery.getSelectItems().size() == 1) {
//            SelectItem item = subquery.getSelectItems().get(0);
//            if (item instanceof ExpressionSelectItem) {
//                subquery.setType(((ExpressionSelectItem) item).getExpression().getType());
//            }
//        }
//    }
//}