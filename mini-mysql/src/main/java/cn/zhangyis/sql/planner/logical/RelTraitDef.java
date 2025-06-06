package cn.zhangyis.sql.planner.logical;

/**
 * 逻辑计划节点属性定义接口
 * 参考Apache Calcite的RelTraitDef
 */
public interface RelTraitDef<T extends RelTrait> {
    /**
     * 获取属性类
     */
    Class<T> getTraitClass();
    
    /**
     * 获取默认属性
     */
    T getDefault();
    
    /**
     * 检查属性是否兼容
     */
    boolean canConvert(RelTrait from, RelTrait to);
    
    /**
     * 转换属性
     */
    T convert(RelTrait from, RelTrait to);
} 