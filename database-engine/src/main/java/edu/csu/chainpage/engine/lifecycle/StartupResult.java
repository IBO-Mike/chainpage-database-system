package edu.csu.chainpage.engine.lifecycle;

import edu.csu.chainpage.engine.contract.TableSchema;

import java.util.List;
import java.util.Objects;

// 表示数据库启动后的结果
public final class StartupResult {

    private final List<TableSchema> tables; // 启动时加载的表结构
    private final boolean ready; // 是否已经可以接收SQL请求

    // 创建启动结果
    public StartupResult(List<TableSchema> tables, boolean ready) {
        this.tables = List.copyOf(Objects.requireNonNull(tables, "tables cannot be null"));
        this.ready = ready;
    }

    // 获取启动时加载的表结构
    public List<TableSchema> getTables() {
        return tables;
    }

    // 获取数据库是否已经就绪
    public boolean isReady() {
        return ready;
    }
}
