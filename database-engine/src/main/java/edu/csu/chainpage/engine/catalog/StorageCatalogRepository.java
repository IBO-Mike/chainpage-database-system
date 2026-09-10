package edu.csu.chainpage.engine.catalog;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.common.JsonCodec;
import edu.csu.chainpage.engine.storage.Row;
import edu.csu.chainpage.engine.storage.StorageEngine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

// 使用StorageEngine把系统目录持久化为一张保留名称的数据表
public final class StorageCatalogRepository implements CatalogRepository {

    // 系统目录表的保留名称，用户表不能使用该名称
    public static final String CATALOG_TABLE = "__system_catalog";

    private static final String TABLE_NAME_COLUMN = "table_name";
    private static final String COLUMN_NAMES_COLUMN = "column_names";
    private static final String COLUMN_TYPES_COLUMN = "column_types";

    private final StorageEngine storageEngine; // 目录使用的存储引擎
    private final JsonCodec jsonCodec; // 用于保存列名和类型数组

    // 使用默认JSON编解码器创建目录仓库
    public StorageCatalogRepository(StorageEngine storageEngine) {
        this(storageEngine, new JsonCodec());
    }

    // 创建可替换JSON编解码器的目录仓库
    public StorageCatalogRepository(StorageEngine storageEngine, JsonCodec jsonCodec) {
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine cannot be null");
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec cannot be null");
    }

    // 确保保留的目录表页映射存在，并登记其表结构
    @Override
    public DbResult<Void> initialize(String requestId) {
        edu.csu.chainpage.engine.storage.TableSchema storageSchema = catalogStorageSchema();
        DbResult<edu.csu.chainpage.engine.contract.TablePages> created =
                storageEngine.createTableStorage(requestId, storageSchema);
        if (!created.isOk() && !"TABLE_ALREADY_EXISTS".equals(created.error().getCode())) {
            return DbResult.fail(created.error());
        }

        DbResult<Void> registered = storageEngine.registerTableSchema(requestId, storageSchema);
        if (!registered.isOk()) {
            return registered;
        }
        return DbResult.ok(null);
    }

    // 扫描目录表并还原所有用户表结构
    @Override
    public DbResult<List<edu.csu.chainpage.engine.contract.TableSchema>> load(String requestId) {
        DbResult<edu.csu.chainpage.engine.storage.RowSet> scanned =
                storageEngine.scanRows(requestId, catalogStorageSchema());
        if (!scanned.isOk()) {
            return DbResult.fail(scanned.error());
        }
        if (scanned.data() == null) {
            return failure(requestId, "SYSTEM_CATALOG_INVALID_RESPONSE", "存储引擎未返回目录行集");
        }

        List<edu.csu.chainpage.engine.contract.TableSchema> tables = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (edu.csu.chainpage.engine.storage.InternalRow internalRow : scanned.data().rows()) {
            DbResult<edu.csu.chainpage.engine.contract.TableSchema> table =
                    fromCatalogRow(internalRow.values());
            if (!table.isOk()) {
                return failureFrom(table.error(), requestId);
            }
            if (!names.add(table.data().getName())) {
                return failure(
                        requestId,
                        "SYSTEM_CATALOG_DUPLICATE_TABLE",
                        "目录中存在重复表定义：" + table.data().getName()
                );
            }
            tables.add(table.data());
        }

        for (edu.csu.chainpage.engine.contract.TableSchema table : tables) {
            DbResult<Void> registered = storageEngine.registerTableSchema(
                    requestId,
                    toStorageSchema(table)
            );
            if (!registered.isOk()) {
                return DbResult.fail(registered.error());
            }
        }
        return DbResult.ok(List.copyOf(tables));
    }

    // 把一张新的用户表结构追加到目录表
    @Override
    public DbResult<Void> save(
            String requestId,
            edu.csu.chainpage.engine.contract.TableSchema schema) {
        DbResult<edu.csu.chainpage.engine.contract.TableSchema> normalized = normalizeSchema(
                requestId,
                schema
        );
        if (!normalized.isOk()) {
            return DbResult.fail(normalized.error());
        }

        DbResult<List<edu.csu.chainpage.engine.contract.TableSchema>> loaded = load(requestId);
        if (!loaded.isOk()) {
            return DbResult.fail(loaded.error());
        }
        for (edu.csu.chainpage.engine.contract.TableSchema table : loaded.data()) {
            if (table.getName().equals(normalized.data().getName())) {
                return failure(
                        requestId,
                        "SYSTEM_CATALOG_TABLE_EXISTS",
                        "目录中已经存在表：" + normalized.data().getName()
                );
            }
        }

        DbResult<Void> registered = storageEngine.registerTableSchema(
                requestId,
                toStorageSchema(normalized.data())
        );
        if (!registered.isOk()) {
            return registered;
        }
        DbResult<edu.csu.chainpage.engine.storage.RowId> inserted = storageEngine.insertRow(
                requestId,
                CATALOG_TABLE,
                toCatalogRow(normalized.data())
        );
        if (!inserted.isOk()) {
            return DbResult.fail(inserted.error());
        }
        return DbResult.ok(null);
    }

    // 从目录表中删除指定用户表定义
    @Override
    public DbResult<Void> remove(String requestId, String table) {
        String normalizedTable = normalizeIdentifier(table);
        if (normalizedTable == null || normalizedTable.isBlank()) {
            return failure(requestId, "SYSTEM_CATALOG_INVALID_TABLE", "表名不能为空");
        }
        if (CATALOG_TABLE.equals(normalizedTable)) {
            return failure(requestId, "SYSTEM_CATALOG_RESERVED_TABLE", "不能删除系统目录表");
        }

        DbResult<edu.csu.chainpage.engine.storage.RowSet> scanned =
                storageEngine.scanRows(requestId, catalogStorageSchema());
        if (!scanned.isOk()) {
            return DbResult.fail(scanned.error());
        }
        if (scanned.data() == null) {
            return failure(requestId, "SYSTEM_CATALOG_INVALID_RESPONSE", "存储引擎未返回目录行集");
        }

        List<edu.csu.chainpage.engine.storage.RowId> rowIds = new ArrayList<>();
        for (edu.csu.chainpage.engine.storage.InternalRow row : scanned.data().rows()) {
            Object value = row.values().valueOf(TABLE_NAME_COLUMN);
            if (value instanceof String name && normalizedTable.equals(name)) {
                rowIds.add(row.rowId());
            }
        }
        if (rowIds.isEmpty()) {
            return failure(requestId, "SYSTEM_CATALOG_TABLE_NOT_FOUND", "目录中不存在表：" + normalizedTable);
        }

        DbResult<Integer> deleted = storageEngine.deleteRows(requestId, CATALOG_TABLE, rowIds);
        if (!deleted.isOk()) {
            return DbResult.fail(deleted.error());
        }
        return DbResult.ok(null);
    }

    // 将目录中的表结构转换为一条存储引擎记录
    public Row toCatalogRow(edu.csu.chainpage.engine.contract.TableSchema schema) {
        Objects.requireNonNull(schema, "schema cannot be null");
        List<String> names = schema.getColumns().stream()
                .map(edu.csu.chainpage.engine.contract.ColumnSchema::getName)
                .toList();
        List<String> types = schema.getColumns().stream()
                .map(edu.csu.chainpage.engine.contract.ColumnSchema::getDataType)
                .toList();
        return new Row(java.util.Map.of(
                TABLE_NAME_COLUMN, schema.getName(),
                COLUMN_NAMES_COLUMN, jsonCodec.write(names),
                COLUMN_TYPES_COLUMN, jsonCodec.write(types)
        ));
    }

    // 将一条目录记录还原成对外的表结构
    public DbResult<edu.csu.chainpage.engine.contract.TableSchema> fromCatalogRow(Row row) {
        if (row == null) {
            return failure(null, "SYSTEM_CATALOG_INVALID_ROW", "目录记录不能为null");
        }
        Object tableValue = row.valueOf(TABLE_NAME_COLUMN);
        Object namesValue = row.valueOf(COLUMN_NAMES_COLUMN);
        Object typesValue = row.valueOf(COLUMN_TYPES_COLUMN);
        if (!(tableValue instanceof String tableName)
                || !(namesValue instanceof String namesJson)
                || !(typesValue instanceof String typesJson)) {
            return failure(null, "SYSTEM_CATALOG_INVALID_ROW", "目录记录字段类型不正确");
        }

        final String[] names;
        final String[] types;
        try {
            names = jsonCodec.read(namesJson, String[].class);
            types = jsonCodec.read(typesJson, String[].class);
        } catch (RuntimeException exception) {
            return failure(null, "SYSTEM_CATALOG_INVALID_ROW", "目录列定义不是合法JSON");
        }
        if (names == null || types == null || names.length != types.length) {
            return failure(null, "SYSTEM_CATALOG_INVALID_ROW", "目录列名和列类型数量不一致");
        }

        String normalizedTable = normalizeIdentifier(tableName);
        if (normalizedTable == null || normalizedTable.isBlank()
                || CATALOG_TABLE.equals(normalizedTable)) {
            return failure(null, "SYSTEM_CATALOG_INVALID_ROW", "目录表名非法");
        }
        List<edu.csu.chainpage.engine.contract.ColumnSchema> columns = new ArrayList<>();
        Set<String> columnNames = new HashSet<>();
        for (int index = 0; index < names.length; index++) {
            String name = normalizeIdentifier(names[index]);
            String dataType = types[index] == null
                    ? null
                    : types[index].toUpperCase(Locale.ROOT);
            if (name == null || name.isBlank() || !columnNames.add(name)
                    || !("INT".equals(dataType) || "VARCHAR".equals(dataType))) {
                return failure(null, "SYSTEM_CATALOG_INVALID_ROW", "目录列定义非法");
            }
            columns.add(new edu.csu.chainpage.engine.contract.ColumnSchema(name, dataType));
        }
        return DbResult.ok(new edu.csu.chainpage.engine.contract.TableSchema(normalizedTable, columns));
    }

    // 返回目录表在存储引擎中的结构
    private edu.csu.chainpage.engine.storage.TableSchema catalogStorageSchema() {
        return new edu.csu.chainpage.engine.storage.TableSchema(
                CATALOG_TABLE,
                List.of(
                        new edu.csu.chainpage.engine.storage.ColumnSchema(TABLE_NAME_COLUMN, "VARCHAR"),
                        new edu.csu.chainpage.engine.storage.ColumnSchema(COLUMN_NAMES_COLUMN, "VARCHAR"),
                        new edu.csu.chainpage.engine.storage.ColumnSchema(COLUMN_TYPES_COLUMN, "VARCHAR")
                )
        );
    }

    // 把对外表结构转换成存储引擎表结构
    private edu.csu.chainpage.engine.storage.TableSchema toStorageSchema(
            edu.csu.chainpage.engine.contract.TableSchema schema) {
        return new edu.csu.chainpage.engine.storage.TableSchema(
                schema.getName(),
                schema.getColumns().stream()
                        .map(column -> new edu.csu.chainpage.engine.storage.ColumnSchema(
                                column.getName(),
                                column.getDataType()
                        ))
                        .toList()
        );
    }

    // 校验并规范化一张对外表结构
    private DbResult<edu.csu.chainpage.engine.contract.TableSchema> normalizeSchema(
            String requestId,
            edu.csu.chainpage.engine.contract.TableSchema schema) {
        if (schema == null || schema.getName() == null
                || schema.getName().isBlank()) {
            return failure(requestId, "SYSTEM_CATALOG_INVALID_SCHEMA", "表结构或表名不能为空");
        }
        String tableName = normalizeIdentifier(schema.getName());
        if (CATALOG_TABLE.equals(tableName)) {
            return failure(requestId, "SYSTEM_CATALOG_RESERVED_TABLE", "不能修改系统目录表");
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

    // 创建目录阶段的统一错误
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

    // 为目录行解析错误补充当前请求编号
    private <T> DbResult<T> failureFrom(DbError error, String requestId) {
        return DbResult.fail(new DbError(
                requestId,
                error.getStatementIndex(),
                error.getStage(),
                error.getCode(),
                error.getMessage(),
                error.getLine(),
                error.getColumn(),
                error.getPageId()
        ));
    }
}
