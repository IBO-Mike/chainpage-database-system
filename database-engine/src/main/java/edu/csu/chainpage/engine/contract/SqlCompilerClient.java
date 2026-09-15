package edu.csu.chainpage.engine.contract;

import edu.csu.chainpage.engine.common.DbResult;

// 数据库引擎调用SQL编译器的接口
public interface SqlCompilerClient {
    // 输入一份SQL编译请求
    // 输出一份编译成功结果或编译错误
    DbResult<CompileResponse> compile(CompileRequest compileRequest);
}
