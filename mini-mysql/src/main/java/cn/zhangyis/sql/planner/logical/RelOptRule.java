package cn.zhangyis.sql.planner.logical;

import java.util.List;
import java.util.Map;

/**
 * 逻辑优化规则接口
 * 参考Apache Calcite的RelOptRule
 */
public interface RelOptRule {
    /**
     * 获取规则名称
     */
    String getName();
    
    /**
     * 获取规则描述
     */
    String getDescription();
    
    /**
     * 获取规则操作数
     */
    List<RelOptRuleOperand> getOperands();
    
    /**
     * 应用规则
     * @param call 规则调用
     */
    void onMatch(RelOptRuleCall call);
    
    /**
     * 规则调用类
     */
    interface RelOptRuleCall {
        /**
         * 获取匹配的节点
         */
        List<RelNode> getRelList();
        
        /**
         * 获取匹配的节点
         */
        RelNode rel(int ordinal);
        
        /**
         * 转换节点
         */
        void transformTo(RelNode rel, Map<RelNode, RelNode> equiv);
    }
    
    /**
     * 规则操作数类
     */
    class RelOptRuleOperand {
        private final Class<? extends RelNode> relClass;
        private final List<RelOptRuleOperand> children;
        
        public RelOptRuleOperand(Class<? extends RelNode> relClass, List<RelOptRuleOperand> children) {
            this.relClass = relClass;
            this.children = children;
        }
        
        public Class<? extends RelNode> getRelClass() {
            return relClass;
        }
        
        public List<RelOptRuleOperand> getChildren() {
            return children;
        }
    }
} 