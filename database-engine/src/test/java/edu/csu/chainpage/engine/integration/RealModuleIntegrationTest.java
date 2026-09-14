package edu.csu.chainpage.engine.integration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

// 在组内真实模块适配器可用时执行三模块端到端联调
class RealModuleIntegrationTest {

    private static final String HARNESS_PROPERTY = "chainpage.realModuleHarness";

    @Test
    void executesCoreFlowAgainstRealCompilerAndStorage() throws Exception {
        try (RealModuleTestHarness harness = createHarness()) {
            harness.executeCoreFlowAgainstRealCompilerAndStorage();
        }
    }

    @Test
    void executesMultiStatementAndErrorCasesAgainstRealModules() throws Exception {
        try (RealModuleTestHarness harness = createHarness()) {
            harness.executeMultiStatementAndErrorCasesAgainstRealModules();
        }
    }

    @Test
    void restartsAgainstRealStorage() throws Exception {
        try (RealModuleTestHarness harness = createHarness()) {
            harness.restartAgainstRealStorage();
        }
    }

    // 从Maven系统属性加载组内提供的真实模块测试适配器
    private RealModuleTestHarness createHarness() throws Exception {
        String className = System.getProperty(HARNESS_PROPERTY);
        Assumptions.assumeTrue(
                className != null && !className.isBlank(),
                "尚未配置真实模块适配器，请通过-D" + HARNESS_PROPERTY + "=实现类全名接入"
        );
        Class<?> type = Class.forName(className);
        Object instance = type.getDeclaredConstructor().newInstance();
        Assumptions.assumeTrue(
                instance instanceof RealModuleTestHarness,
                "真实模块适配器必须实现RealModuleTestHarness"
        );
        return (RealModuleTestHarness) instance;
    }
}
