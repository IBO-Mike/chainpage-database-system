package edu.csu.chainpage.engine.executor;

import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.plan.PlanNode;

// 表示一种计划节点的执行器
public interface PlanExecutor {

    // 执行一个计划节点
    DbResult<ExecutionValue> execute(String requestId, PlanNode plan);

    // 判断当前执行器是否支持指定节点种类
    boolean supports(String kind);
}
