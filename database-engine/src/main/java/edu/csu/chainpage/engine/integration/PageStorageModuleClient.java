package edu.csu.chainpage.engine.integration;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.common.JsonCodec;
import edu.csu.chainpage.engine.contract.AllocatedPage;
import edu.csu.chainpage.engine.contract.DropTablePagesResult;
import edu.csu.chainpage.engine.contract.FlushResult;
import edu.csu.chainpage.engine.contract.PageData;
import edu.csu.chainpage.engine.contract.PageStorageClient;
import edu.csu.chainpage.engine.contract.TablePages;
import edu.csu.chainpage.engine.contract.WritePageResult;
import org.chainpage.storage.StorageCli;
import org.chainpage.storage.StorageManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 使用页式存储模块的公开 JSON 操作实现引擎所需的七项页服务。 */
public final class PageStorageModuleClient implements PageStorageClient {
    private final StorageManager storage;
    private final JsonCodec json = new JsonCodec();

    public PageStorageModuleClient(StorageManager storage) {
        this.storage = Objects.requireNonNull(storage, "storage cannot be null");
    }

    @Override
    public DbResult<PageData> getPage(String requestId, int pageId) {
        return call(requestId, "get_page", Map.of("pageId", pageId), PageData.class);
    }

    @Override
    public DbResult<WritePageResult> writePage(String requestId, int pageId, String base64Data) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("pageId", pageId);
        fields.put("data", base64Data);
        return call(requestId, "write_page", fields, WritePageResult.class);
    }

    @Override
    public DbResult<TablePages> createTablePages(String requestId, String table) {
        return tableCall(requestId, "create_table_pages", table, TablePages.class);
    }

    @Override
    public DbResult<DropTablePagesResult> dropTablePages(String requestId, String table) {
        return tableCall(requestId, "drop_table_pages", table, DropTablePagesResult.class);
    }

    @Override
    public DbResult<AllocatedPage> allocatePageForTable(String requestId, String table) {
        return tableCall(requestId, "allocate_page_for_table", table, AllocatedPage.class);
    }

    @Override
    public DbResult<TablePages> listTablePages(String requestId, String table) {
        return tableCall(requestId, "list_table_pages", table, TablePages.class);
    }

    @Override
    public DbResult<FlushResult> flushAll(String requestId) {
        return call(requestId, "flush_all", Map.of(), FlushResult.class);
    }

    private <T> DbResult<T> tableCall(String requestId, String op, String table, Class<T> type) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("table", table);
        return call(requestId, op, fields, type);
    }

    private <T> DbResult<T> call(String requestId, String op, Map<String, Object> fields, Class<T> type) {
        Map<String, Object> request = new LinkedHashMap<>(fields);
        request.put("requestId", requestId);
        request.put("op", op);
        try {
            Map<String, Object> response = StorageCli.handle(storage, request);
            if (Boolean.FALSE.equals(response.get("ok"))) {
                return DbResult.fail(error(response.get("error"), requestId));
            }
            if (!Boolean.TRUE.equals(response.get("ok")) || !(response.get("data") instanceof Map<?, ?> data)
                    || !Objects.equals(requestId, data.get("requestId"))) {
                return invalid(requestId, "页式存储返回了无效成功响应");
            }
            return DbResult.ok(json.read(json.write(data), type));
        } catch (RuntimeException exception) {
            return invalid(requestId, "页式存储响应无法转换：" + exception.getMessage());
        }
    }

    private static DbError error(Object raw, String requestId) {
        if (!(raw instanceof Map<?, ?> error)
                || !(error.get("stage") instanceof String stage)
                || !(error.get("code") instanceof String code)
                || !(error.get("message") instanceof String message)) {
            return new DbError(requestId, null, "PAGE", "STORAGE_INVALID_RESPONSE",
                    "页式存储返回了无效错误对象", null, null, null);
        }
        return new DbError(requestId, number(error.get("statementIndex")), stage, code, message,
                number(error.get("line")), number(error.get("column")), number(error.get("pageId")));
    }

    private static Integer number(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static <T> DbResult<T> invalid(String requestId, String message) {
        return DbResult.fail(new DbError(requestId, null, "PAGE", "STORAGE_INVALID_RESPONSE",
                message, null, null, null));
    }
}
