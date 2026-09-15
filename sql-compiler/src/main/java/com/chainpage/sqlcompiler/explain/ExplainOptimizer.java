package com.chainpage.sqlcompiler.explain;

import com.chainpage.sqlcompiler.optimizer.*;
import java.util.Map;

/** EXPLAIN 与普通编译使用同一个优化入口。 */
public final class ExplainOptimizer {
    public OptimizeResponse optimize(Map<String, Object> plan) {
        return new Optimizer().optimize(new OptimizeRequest(plan));
    }
}
