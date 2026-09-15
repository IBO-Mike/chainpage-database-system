package edu.csu.chainpage.engine.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

// 表示数据库引擎在编译前提供给SQL编译器的只读目录快照
public final class CatalogSnapshot {

    private final List<TableSchema> tables; // 当前数据库中的表结构

    // 创建目录快照
    @JsonCreator
    public CatalogSnapshot(
            @JsonProperty("tables") List<TableSchema> tables) {
        this.tables = List.copyOf(Objects.requireNonNull(tables, "tables cannot be null"));
    }

    // 获取目录中的全部表结构
    public List<TableSchema> getTables() {
        return tables;
    }
}
