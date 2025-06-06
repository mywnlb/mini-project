package cn.zhangyis.sql.planner.physical;

import java.util.List;

/**
 * 执行结果类
 * 表示物理操作符的执行结果
 */
public class ExecutionResult {
    private final List<Object[]> rows;
    private final long executionTime;
    
    public ExecutionResult(List<Object[]> rows, long executionTime) {
        this.rows = rows;
        this.executionTime = executionTime;
    }
    
    /**
     * 获取结果行
     */
    public List<Object[]> getRows() {
        return rows;
    }
    
    /**
     * 获取执行时间
     */
    public long getExecutionTime() {
        return executionTime;
    }
} 