package edu.csu.chainpage.engine.api;

import edu.csu.chainpage.engine.common.DbResult;

// 为数据库入口执行一条逻辑计划的接口
@FunctionalInterface
public interface StatementExecutor {

    // 执行一条编译器生成的计划
    DbResult<StatementExecutionResult> execute(
            String requestId,
            int statementIndex,
            Object plan
    );
}
