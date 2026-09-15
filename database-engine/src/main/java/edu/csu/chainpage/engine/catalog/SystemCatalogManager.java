package edu.csu.chainpage.engine.catalog;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.CatalogSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

// 管理系统目录的内存视图，并把目录变化委托给CatalogRepository持久化
public final class SystemCatalogManager {

    private final CatalogRepository repository; // 目录持久化仓库
    private final Map<String, edu.csu.chainpage.engine.contract.TableSchema> tables =
            new LinkedHashMap<>(); // 按加载顺序保存表结构

    // 创建系统目录管理器
    public SystemCatalogManager(CatalogRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
    }

    // 初始化目录存储并加载当前持久化表结构
    public synchronized DbResult<Void> initialize(String requestId) {
        DbResult<Void> initialized = repository.initialize(requestId);
        if (!initialized.isOk()) {
            return initialized;
        }
        DbResult<List<edu.csu.chainpage.engine.contract.TableSchema>> loaded = load(requestId);
        if (!loaded.isOk()) {
            return DbResult.fail(loaded.error());
        }
        return DbResult.ok(null);
    }

    // 创建表并在持久化成功后更新内存目录
    public synchronized DbResult<edu.csu.chainpage.engine.contract.TableSchema> createTable(
            String requestId,
            edu.csu.chainpage.engine.contract.TableSchema schema) {
        DbResult<edu.csu.chainpage.engine.contract.TableSchema> normalized = normalizeSchema(
                requestId,
                schema
        );
        if (!normalized.isOk()) {
            return normalized;
        }
        String tableName = normalized.data().getName();
        if (tables.containsKey(tableName)) {
            return failure(
                    requestId,
                    "SYSTEM_CATALOG_TABLE_EXISTS",
                    "目录中已经存在表：" + tableName
            );
        }

        DbResult<Void> saved = repository.save(requestId, normalized.data());
        if (!saved.isOk()) {
            return DbResult.fail(saved.error());
        }
        tables.put(tableName, normalized.data());
        return DbResult.ok(normalized.data());
    }

    // 按大小写不敏感的表名查询表结构
    public synchronized DbResult<Optional<edu.csu.chainpage.engine.contract.TableSchema>> getTable(
            String requestId,
            String table) {
        String normalizedTable = normalizeIdentifier(table);
        if (normalizedTable == null || normalizedTable.isBlank()) {
            return failure(requestId, "SYSTEM_CATALOG_INVALID_TABLE", "表名不能为空");
        }
        return DbResult.ok(Optional.ofNullable(tables.get(normalizedTable)));
    }

    // 重新从持久化目录加载并替换当前内存视图
    public synchronized DbResult<List<edu.csu.chainpage.engine.contract.TableSchema>> load(
            String requestId) {
        DbResult<List<edu.csu.chainpage.engine.contract.TableSchema>> loaded = repository.load(requestId);
        if (!loaded.isOk()) {
            return loaded;
        }
        if (loaded.data() == null) {
            return failure(requestId, "SYSTEM_CATALOG_INVALID_RESPONSE", "目录仓库未返回表结构列表");
        }

        Map<String, edu.csu.chainpage.engine.contract.TableSchema> replacement = new LinkedHashMap<>();
        for (edu.csu.chainpage.engine.contract.TableSchema schema : loaded.data()) {
            DbResult<edu.csu.chainpage.engine.contract.TableSchema> normalized = normalizeSchema(
                    requestId,
                    schema
            );
            if (!normalized.isOk()) {
                return DbResult.fail(normalized.error());
            }
            if (replacement.put(normalized.data().getName(), normalized.data()) != null) {
                return failure(
                        requestId,
                        "SYSTEM_CATALOG_DUPLICATE_TABLE",
                        "目录中存在重复表定义：" + normalized.data().getName()
                );
            }
        }

        tables.clear();
        tables.putAll(replacement);
        return DbResult.ok(List.copyOf(tables.values()));
    }

    // 构造供SQL编译器使用的不可变目录快照
    public synchronized CatalogSnapshot snapshot() {
        return new CatalogSnapshot(new ArrayList<>(tables.values()));
    }

    // 删除持久化目录登记，并在成功后删除内存视图
    public synchronized DbResult<Void> removeTable(String requestId, String table) {
        String normalizedTable = normalizeIdentifier(table);
        if (normalizedTable == null || normalizedTable.isBlank()) {
            return failure(requestId, "SYSTEM_CATALOG_INVALID_TABLE", "表名不能为空");
        }
        if (StorageCatalogRepository.CATALOG_TABLE.equals(normalizedTable)) {
            return failure(requestId, "SYSTEM_CATALOG_RESERVED_TABLE", "不能删除系统目录表");
        }
        if (!tables.containsKey(normalizedTable)) {
            return failure(requestId, "SYSTEM_CATALOG_TABLE_NOT_FOUND", "目录中不存在表：" + normalizedTable);
        }

        DbResult<Void> removed = repository.remove(requestId, normalizedTable);
        if (!removed.isOk()) {
            return removed;
        }
        tables.remove(normalizedTable);
        return DbResult.ok(null);
    }

    // 判断内存目录中是否已经登记指定表
    public synchronized boolean containsTable(String table) {
        String normalizedTable = normalizeIdentifier(table);
        return normalizedTable != null && tables.containsKey(normalizedTable);
    }

    // 校验并规范化对外的表结构
    private DbResult<edu.csu.chainpage.engine.contract.TableSchema> normalizeSchema(
            String requestId,
            edu.csu.chainpage.engine.contract.TableSchema schema) {
        if (schema == null || schema.getName() == null || schema.getName().isBlank()) {
            return failure(requestId, "SYSTEM_CATALOG_INVALID_SCHEMA", "表结构或表名不能为空");
        }
        String tableName = normalizeIdentifier(schema.getName());
        if (StorageCatalogRepository.CATALOG_TABLE.equals(tableName)) {
            return failure(requestId, "SYSTEM_CATALOG_RESERVED_TABLE", "不能使用系统目录保留名称");
        }
        if (schema.getColumns() == null) {
            return failure(requestId, "SYSTEM_CATALOG_INVALID_SCHEMA", "列定义不能为null");
        }

        Set<String> names = new HashSet<>();
        List<edu.csu.chainpage.engine.contract.ColumnSchema> columns = new ArrayList<>();
        for (edu.csu.chainpage.engine.contract.ColumnSchema column : schema.getColumns()) {
            if (column == null || column.getName() == null || column.getName().isBlank()
                    || column.getDataType() == null) {
                return failure(requestId, "SYSTEM_CATALOG_INVALID_SCHEMA", "列定义不完整");
            }
            String name = normalizeIdentifier(column.getName());
            String dataType = column.getDataType().toUpperCase(Locale.ROOT);
            if (!names.add(name) || !("INT".equals(dataType) || "VARCHAR".equals(dataType))) {
                return failure(requestId, "SYSTEM_CATALOG_INVALID_SCHEMA", "列名重复或列类型不支持");
            }
            columns.add(new edu.csu.chainpage.engine.contract.ColumnSchema(name, dataType));
        }
        return DbResult.ok(new edu.csu.chainpage.engine.contract.TableSchema(tableName, columns));
    }

    // 统一规范化表名和列名
    private String normalizeIdentifier(String identifier) {
        return identifier == null ? null : identifier.toLowerCase(Locale.ROOT);
    }

    // 创建目录管理器的统一错误
    private <T> DbResult<T> failure(String requestId, String code, String message) {
        return DbResult.fail(new DbError(
                requestId,
                null,
                "CATALOG",
                code,
                message,
                null,
                null,
                null
        ));
    }
}
