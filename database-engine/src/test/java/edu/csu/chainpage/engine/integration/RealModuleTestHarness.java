package edu.csu.chainpage.engine.integration;

// 由组内真实SQL编译器和页式存储适配器实现的联调测试入口
public interface RealModuleTestHarness extends AutoCloseable {

    // 使用真实模块执行建表、插入、查询和删除
    void executeCoreFlowAgainstRealCompilerAndStorage() throws Exception;

    // 使用真实模块执行多语句和错误传播场景
    void executeMultiStatementAndErrorCasesAgainstRealModules() throws Exception;

    // 使用真实页式存储关闭并重新启动数据库
    void restartAgainstRealStorage() throws Exception;

    // 默认没有额外资源需要关闭
    @Override
    default void close() throws Exception {
    }
}
