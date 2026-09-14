package com.chainpage.sqlcompiler.extension;

import java.util.Map;
import java.util.Set;

/** 内存参考执行器适配口；正式 Dispatcher 使用 {plan: Plan}。 */
public interface ExtensionExecutor {
    Set<String> supportedPlanKinds();
    Set<String> supportedTypes();
    ExtensionResponse execute(Map<String, Object> request);
}
