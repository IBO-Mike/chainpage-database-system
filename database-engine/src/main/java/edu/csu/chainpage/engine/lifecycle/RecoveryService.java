package edu.csu.chainpage.engine.lifecycle;

import edu.csu.chainpage.engine.catalog.StorageCatalogRepository;
import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.CatalogSnapshot;
import edu.csu.chainpage.engine.contract.ColumnSchema;
import edu.csu.chainpage.engine.contract.TableSchema;
import edu.csu.chainpage.engine.storage.StorageEngine;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

// 负责在数据库启动时恢复目录并检查每张表的持久化数据
public final class RecoveryService {

    private final CatalogLoader catalogLoader; // 持久化系统目录加载器
    private final StorageEngine storageEngine; // 用于检查表页映射和数据页的存储引擎

    // 创建数据库恢复服务
    public RecoveryService(CatalogLoader catalogLoader, StorageEngine storageEngine) {
        this.catalogLoader = Objects.requireNonNull(catalogLoader, "catalogLoader cannot be null");
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine cannot be null");
    }

    // 加载目录、校验目录内容并逐表检查持久化存储
    public DbResult<List<TableSchema>> recover(String requestId) {
        DbResult<CatalogSnapshot> loaded = catalogLoader.load(requestId);
        if (loaded == null) {
            return failure(requestId, "RECOVERY_INVALID_CATALOG_RESPONSE", "目录加载器未返回结果");
        }
        if (!loaded.isOk()) {
            return DbResult.fail(loaded.error());
        }
        if (loaded.data() == null || loaded.data().getTables() == null) {
            return failure(requestId, "RECOVERY_INVALID_CATALOG_RESPONSE", "目录加载器未返回表结构");
        }

        List<TableSchema> tables = loaded.data().getTables();
        DbResult<Void> catalogVerification = verifyCatalog(requestId, tables);
        if (!catalogVerification.isOk()) {
            return DbResult.fail(catalogVerification.error());
        }
        for (TableSchema table : tables) {
            DbResult<Void> storageVerification = verifyTableStorage(requestId, table);
            if (!storageVerification.isOk()) {
                return DbResult.fail(storageVerification.error());
            }
        }
        return DbResult.ok(List.copyOf(tables));
    }

    // 检查目录中的一张表能否通过存储引擎读取
    public DbResult<Void> verifyTableStorage(String requestId, TableSchema schema) {
        if (schema == null) {
            return failure(requestId, "RECOVERY_INVALID_CATALOG", "目录中不能包含null表结构");
        }
        DbResult<Void> schemaVerification = verifyCatalog(requestId, List.of(schema));
        if (!schemaVerification.isOk()) {
            return schemaVerification;
        }
        edu.csu.chainpage.engine.storage.TableSchema storageSchema =
                new edu.csu.chainpage.engine.storage.TableSchema(
                        schema.getName(),
                        schema.getColumns().stream()
                                .map(column -> new edu.csu.chainpage.engine.storage.ColumnSchema(
                                        column.getName(),
                                        column.getDataType()
                                ))
                                .toList()
                );
        return storageEngine.verifyTable(requestId, storageSchema);
    }

    // 检查目录中的表名、列名和列类型，并拒绝重复定义
    public DbResult<Void> verifyCatalog(String requestId, List<TableSchema> tables) {
        if (tables == null) {
            return failure(requestId, "RECOVERY_INVALID_CATALOG", "目录表结构列表不能为null");
        }

        Set<String> tableNames = new HashSet<>();
        for (TableSchema table : tables) {
            if (table == null || table.getName() == null || table.getName().isBlank()) {
                return failure(requestId, "RECOVERY_INVALID_CATALOG", "目录中存在无效表定义");
            }
            String tableName = table.getName().toLowerCase(Locale.ROOT);
            if (StorageCatalogRepository.CATALOG_TABLE.equals(tableName)) {
                return failure(requestId, "RECOVERY_INVALID_CATALOG", "用户目录包含系统保留表");
            }
            if (!tableNames.add(tableName)) {
                return failure(
                        requestId,
                        "RECOVERY_DUPLICATE_TABLE",
                        "目录中存在重复表定义：" + tableName
                );
            }
            if (table.getColumns() == null) {
                return failure(requestId, "RECOVERY_INVALID_CATALOG", "表的列定义不能为null");
            }

            Set<String> columnNames = new HashSet<>();
            for (ColumnSchema column : table.getColumns()) {
                if (column == null || column.getName() == null || column.getName().isBlank()
                        || column.getDataType() == null) {
                    return failure(requestId, "RECOVERY_INVALID_CATALOG", "目录中存在无效列定义");
                }
                String columnName = column.getName().toLowerCase(Locale.ROOT);
                String dataType = column.getDataType().toUpperCase(Locale.ROOT);
                if (!columnNames.add(columnName)) {
                    return failure(
                            requestId,
                            "RECOVERY_DUPLICATE_COLUMN",
                            "目录中存在重复列定义：" + tableName + "." + columnName
                    );
                }
                if (!("INT".equals(dataType) || "VARCHAR".equals(dataType))) {
                    return failure(
                            requestId,
                            "RECOVERY_UNSUPPORTED_COLUMN_TYPE",
                            "目录中存在不支持的列类型：" + dataType
                    );
                }
            }
        }
        return DbResult.ok(null);
    }

    // 创建恢复阶段的统一错误
    private <T> DbResult<T> failure(String requestId, String code, String message) {
        return DbResult.fail(new DbError(
                requestId,
                null,
                "LIFECYCLE",
                code,
                message,
                null,
                null,
                null
        ));
    }
}
