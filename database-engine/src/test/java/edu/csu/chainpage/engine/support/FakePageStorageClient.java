package edu.csu.chainpage.engine.support;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.AllocatedPage;
import edu.csu.chainpage.engine.contract.DropTablePagesResult;
import edu.csu.chainpage.engine.contract.FlushResult;
import edu.csu.chainpage.engine.contract.PageData;
import edu.csu.chainpage.engine.contract.PageStorageClient;
import edu.csu.chainpage.engine.contract.TablePages;
import edu.csu.chainpage.engine.contract.WritePageResult;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// 用于独立测试的页式存储系统模拟客户端
public final class FakePageStorageClient implements PageStorageClient {

    public static final String GET_PAGE = "get_page";
    public static final String WRITE_PAGE = "write_page";
    public static final String CREATE_TABLE_PAGES = "create_table_pages";
    public static final String DROP_TABLE_PAGES = "drop_table_pages";
    public static final String ALLOCATE_PAGE_FOR_TABLE = "allocate_page_for_table";
    public static final String LIST_TABLE_PAGES = "list_table_pages";
    public static final String FLUSH_ALL = "flush_all";

    private final Map<Integer, PageData> pages = new HashMap<>(); // 模拟页内容
    private final Map<String, List<Integer>> tablePages = new HashMap<>(); // 模拟表页映射
    private final Map<String, Integer> callCounts = new HashMap<>(); // 各操作调用次数
    private final Map<String, DbError> failures = new HashMap<>(); // 下一次操作的模拟失败
    private int nextPageId; // 下一个可分配的页号

    // 读取一个已经分配的页
    @Override
    public DbResult<PageData> getPage(String requestId, int pageId) {
        String operation = GET_PAGE;
        recordCall(operation);
        DbResult<PageData> failure = takeFailure(operation);
        if (failure != null) {
            return failure;
        }

        PageData page = pages.get(pageId);
        if (page == null) {
            return DbResult.fail(storageError(
                    requestId,
                    "PAGE_NOT_ALLOCATED",
                    "页未分配",
                    pageId
            ));
        }

        return DbResult.ok(new PageData(
                requestId,
                page.getPageId(),
                page.getData(),
                true,
                page.isDirty()
        ));
    }

    // 把完整内容写入指定页
    @Override
    public DbResult<WritePageResult> writePage(
            String requestId,
            int pageId,
            String base64Data) {
        String operation = WRITE_PAGE;
        recordCall(operation);
        DbResult<WritePageResult> failure = takeFailure(operation);
        if (failure != null) {
            return failure;
        }

        if (!pages.containsKey(pageId)) {
            return DbResult.fail(storageError(
                    requestId,
                    "PAGE_NOT_ALLOCATED",
                    "页未分配",
                    pageId
            ));
        }
        if (base64Data == null) {
            return DbResult.fail(storageError(
                    requestId,
                    "INVALID_PAGE_DATA",
                    "页数据不能为null",
                    pageId
            ));
        }
        try {
            if (Base64.getDecoder().decode(base64Data).length != 4096) {
                return DbResult.fail(storageError(
                        requestId,
                        "INVALID_PAGE_SIZE",
                        "页数据解码后必须是4096字节",
                        pageId
                ));
            }
        } catch (IllegalArgumentException exception) {
            return DbResult.fail(storageError(
                    requestId,
                    "INVALID_PAGE_DATA",
                    "页数据不是合法的Base64字符串",
                    pageId
            ));
        }

        pages.put(pageId, new PageData(requestId, pageId, base64Data, false, true));
        return DbResult.ok(new WritePageResult(requestId, pageId, true));
    }

    // 为新表创建一个空的表页映射
    @Override
    public DbResult<TablePages> createTablePages(String requestId, String table) {
        String operation = CREATE_TABLE_PAGES;
        recordCall(operation);
        DbResult<TablePages> failure = takeFailure(operation);
        if (failure != null) {
            return failure;
        }

        if (tablePages.containsKey(table)) {
            return DbResult.fail(storageError(
                    requestId,
                    "TABLE_ALREADY_EXISTS",
                    "表页映射已经存在",
                    null
            ));
        }

        tablePages.put(table, new ArrayList<>());
        return DbResult.ok(new TablePages(requestId, table, List.of()));
    }

    // 删除页表映射，释放该表的所有数据页
    @Override
    public DbResult<DropTablePagesResult> dropTablePages(String requestId, String table) {
        String operation = DROP_TABLE_PAGES;
        recordCall(operation);
        DbResult<DropTablePagesResult> failure = takeFailure(operation);
        if (failure != null) {
            return failure;
        }

        List<Integer> pageIds = tablePages.remove(table);
        if (pageIds == null) {
            return DbResult.fail(storageError(
                    requestId,
                    "TABLE_NOT_FOUND",
                    "表页映射不存在",
                    null
            ));
        }

        for (Integer pageId : pageIds) {
            pages.remove(pageId);
        }

        return DbResult.ok(new DropTablePagesResult(
                requestId,
                table,
                true,
                List.copyOf(pageIds)
        ));
    }

    // 为指定表分配一个新页，并同时更新表页映射
    @Override
    public DbResult<AllocatedPage> allocatePageForTable(
            String requestId,
            String table) {
        String operation = ALLOCATE_PAGE_FOR_TABLE;
        recordCall(operation);
        DbResult<AllocatedPage> failure = takeFailure(operation);
        if (failure != null) {
            return failure;
        }

        List<Integer> pageIds = tablePages.get(table);
        if (pageIds == null) {
            return DbResult.fail(storageError(
                    requestId,
                    "TABLE_NOT_FOUND",
                    "表页映射不存在",
                    null
            ));
        }

        int pageId = nextPageId;
        while (pages.containsKey(pageId)) {
            pageId++;
        }
        nextPageId = pageId + 1;

        pages.put(pageId, new PageData(
                requestId,
                pageId,
                emptyPageData(),
                false,
                false
        ));
        pageIds.add(pageId);

        return DbResult.ok(new AllocatedPage(
                requestId,
                table,
                pageId,
                List.copyOf(pageIds)
        ));
    }

    // 取得某张表当前所有数据页的页号
    @Override
    public DbResult<TablePages> listTablePages(String requestId, String table) {
        String operation = LIST_TABLE_PAGES;
        recordCall(operation);
        DbResult<TablePages> failure = takeFailure(operation);
        if (failure != null) {
            return failure;
        }

        List<Integer> pageIds = tablePages.get(table);
        if (pageIds == null) {
            return DbResult.fail(storageError(
                    requestId,
                    "TABLE_NOT_FOUND",
                    "表页映射不存在",
                    null
            ));
        }

        return DbResult.ok(new TablePages(requestId, table, List.copyOf(pageIds)));
    }

    // 将所有脏页写回持久化存储
    @Override
    public DbResult<FlushResult> flushAll(String requestId) {
        String operation = FLUSH_ALL;
        recordCall(operation);
        DbResult<FlushResult> failure = takeFailure(operation);
        if (failure != null) {
            return failure;
        }

        List<Integer> flushedPageIds = new ArrayList<>();
        for (Map.Entry<Integer, PageData> entry : pages.entrySet()) {
            PageData page = entry.getValue();
            if (page.isDirty()) {
                flushedPageIds.add(entry.getKey());
                pages.put(entry.getKey(), new PageData(
                        requestId,
                        page.getPageId(),
                        page.getData(),
                        page.isHit(),
                        false
                ));
            }
        }

        return DbResult.ok(new FlushResult(requestId, flushedPageIds));
    }

    // 预置下一次指定操作的失败结果
    public void failNext(String operation, DbError error) {
        failures.put(
                Objects.requireNonNull(operation, "operation cannot be null"),
                Objects.requireNonNull(error, "error cannot be null")
        );
    }

    // 获取指定操作已经调用的次数
    public int callCount(String operation) {
        return callCounts.getOrDefault(operation, 0);
    }

    // 清空操作调用记录
    public void clearCalls() {
        callCounts.clear();
    }

    // 记录一次操作调用
    private void recordCall(String operation) {
        callCounts.merge(operation, 1, Integer::sum);
    }

    // 取得并移除指定操作的下一次失败结果
    @SuppressWarnings("unchecked")
    private <T> DbResult<T> takeFailure(String operation) {
        DbError error = failures.remove(operation);
        if (error == null) {
            return null;
        }
        return DbResult.fail(error);
    }

    // 创建统一格式的页式存储错误
    private DbError storageError(
            String requestId,
            String code,
            String message,
            Integer pageId) {
        return new DbError(
                requestId,
                null,
                "STORAGE",
                code,
                message,
                null,
                null,
                pageId
        );
    }

    // 创建一页4096字节的零值页内容
    private String emptyPageData() {
        return Base64.getEncoder().encodeToString(new byte[4096]);
    }
}
