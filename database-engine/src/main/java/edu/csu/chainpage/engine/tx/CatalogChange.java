package edu.csu.chainpage.engine.tx;

import edu.csu.chainpage.engine.contract.TableSchema;

import java.util.Locale;
import java.util.Objects;

// 保存事务中发生的一次系统目录变更
public final class CatalogChange {

    private final String operation; // 目录操作名称
    private final TableSchema schema; // 被修改的表结构

    // 创建目录变更记录
    public CatalogChange(String operation, TableSchema schema) {
        this.operation = Objects.requireNonNull(operation, "operation cannot be null")
                .toUpperCase(Locale.ROOT);
        if (operation.isBlank()) {
            throw new IllegalArgumentException("operation cannot be blank");
        }
        this.schema = Objects.requireNonNull(schema, "schema cannot be null");
    }

    // 返回目录操作名称
    public String operation() {
        return operation;
    }

    // 返回被修改的表结构
    public TableSchema schema() {
        return schema;
    }
}
