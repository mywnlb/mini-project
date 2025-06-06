package cn.zhangyis.sql.planner.logical;

/**
 * 逻辑计划节点属性接口
 * 参考Apache Calcite的RelTrait
 */
public interface RelTrait {
    /**
     * 获取属性类型
     */
    RelTraitDef getTraitDef();
    
    /**
     * 检查是否满足属性要求
     */
    boolean satisfies(RelTrait trait);
    
    /**
     * 注册属性定义
     */
    void register(RelTraitDef traitDef);
} 