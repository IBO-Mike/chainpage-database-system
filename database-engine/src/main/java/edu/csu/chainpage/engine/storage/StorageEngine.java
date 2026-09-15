package edu.csu.chainpage.engine.storage;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.AllocatedPage;
import edu.csu.chainpage.engine.contract.DropTablePagesResult;
import edu.csu.chainpage.engine.contract.PageData;
import edu.csu.chainpage.engine.contract.PageStorageClient;
import edu.csu.chainpage.engine.contract.TablePages;
import edu.csu.chainpage.engine.storage.index.IndexLookup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// 面向表和记录提供存储访问，所有页读写都通过PageStorageClient完成
public final class StorageEngine {

    private final PageStorageClient pageStorageClient; // 页式存储系统客户端
    private final PageRecordCodec recordCodec; // 记录编解码器
    private final DataPageCodec dataPageCodec; // 数据页编解码器
    private final IndexLookup indexLookup; // 可替换的底层索引访问能力
    private final Map<String, TableSchema> schemas = new HashMap<>(); // 当前引擎已登记的表结构

    // 使用默认记录和数据页编解码器创建存储引擎
    public StorageEngine(PageStorageClient pageStorageClient) {
        this(
                pageStorageClient,
                new PageRecordCodec(),
                new DataPageCodec(),
                new UnavailableIndexLookup()
        );
    }

    // 创建使用指定底层索引能力的存储引擎
    public StorageEngine(PageStorageClient pageStorageClient, IndexLookup indexLookup) {
        this(pageStorageClient, new PageRecordCodec(), new DataPageCodec(), indexLookup);
    }

    // 创建可替换编解码器的存储引擎，便于独立测试
    public StorageEngine(
            PageStorageClient pageStorageClient,
            PageRecordCodec recordCodec,
            DataPageCodec dataPageCodec) {
        this(pageStorageClient, recordCodec, dataPageCodec, new UnavailableIndexLookup());
    }

    // 创建可替换编解码器和底层索引能力的存储引擎
    public StorageEngine(
            PageStorageClient pageStorageClient,
            PageRecordCodec recordCodec,
            DataPageCodec dataPageCodec,
            IndexLookup indexLookup) {
        this.pageStorageClient = Objects.requireNonNull(pageStorageClient, "pageStorageClient cannot be null");
        this.recordCodec = Objects.requireNonNull(recordCodec, "recordCodec cannot be null");
        this.dataPageCodec = Objects.requireNonNull(dataPageCodec, "dataPageCodec cannot be null");
        this.indexLookup = Objects.requireNonNull(indexLookup, "indexLookup cannot be null");
    }

    // 登记已经存在于页式存储系统中的表结构，供目录加载和重启恢复使用
    public DbResult<Void> registerTableSchema(String requestId, TableSchema schema) {
        if (schema == null) {
            return failure(requestId, "INVALID_SCHEMA", "表结构不能为null", null);
        }
        DbResult<Void> schemaResult = recordCodec.validateSchema(schema);
        if (!schemaResult.isOk()) {
            return failureFrom(schemaResult.error(), requestId, null);
        }

        String table = schema.getName();
        TableSchema registered = schemas.get(table);
        if (registered != null && !sameSchema(registered, schema)) {
            return failure(requestId, "TABLE_SCHEMA_CONFLICT", "表结构与已登记结构不一致：" + table, null);
        }
        schemas.put(table, schema);
        return DbResult.ok(null);
    }

    // 验证表页映射存在，并读取和解码该表的全部数据页
    public DbResult<Void> verifyTable(String requestId, TableSchema schema) {
        DbResult<Void> registered = registerTableSchema(requestId, schema);
        if (!registered.isOk()) {
            return registered;
        }
        DbResult<RowSet> scanned = scanRows(requestId, schema);
        if (!scanned.isOk()) {
            return DbResult.fail(scanned.error());
        }
        if (scanned.data() == null) {
            return failure(requestId, "INVALID_STORAGE_RESPONSE", "存储验证未返回表行集", null);
        }
        return DbResult.ok(null);
    }

    // 创建表的空页映射，建表阶段不提前分配数据页
    public DbResult<TablePages> createTableStorage(String requestId, TableSchema schema) {
        if (schema == null) {
            return failure(requestId, "INVALID_SCHEMA", "表结构不能为null", null);
        }
        DbResult<Void> schemaResult = recordCodec.validateSchema(schema);
        if (!schemaResult.isOk()) {
            return failureFrom(schemaResult.error(), requestId, null);
        }
        String table = schema.getName();
        if (schemas.containsKey(table)) {
            return failure(requestId, "TABLE_ALREADY_EXISTS", "表结构已经登记：" + table, null);
        }

        DbResult<TablePages> created = pageStorageClient.createTablePages(requestId, table);
        if (!created.isOk()) {
            return failureFrom(created.error(), requestId, null);
        }
        if (created.data() == null) {
            return failure(requestId, "INVALID_STORAGE_RESPONSE", "页式存储系统未返回表页映射", null);
        }
        schemas.put(table, schema);
        return created;
    }

    // 将一条记录写入已有页；已有页全部无空间时再申请新页
    public DbResult<RowId> insertRow(String requestId, String table, Row row) {
        String normalizedTable = normalizeTable(table);
        if (normalizedTable == null) {
            return failure(requestId, "TABLE_NOT_FOUND", "表名不能为null", null);
        }
        TableSchema schema = schemas.get(normalizedTable);
        if (schema == null) {
            return failure(requestId, "TABLE_NOT_FOUND", "表结构不存在：" + normalizedTable, null);
        }

        DbResult<byte[]> encoded = recordCodec.encode(schema, row);
        if (!encoded.isOk()) {
            return failureFrom(encoded.error(), requestId, null);
        }
        if (!new DataPage().canFit(encoded.data().length)) {
            return failure(requestId, "RECORD_TOO_LARGE", "记录无法放入一页", null);
        }

        DbResult<List<Integer>> pageIdsResult = listTablePageIds(requestId, normalizedTable);
        if (!pageIdsResult.isOk()) {
            return DbResult.fail(pageIdsResult.error());
        }
        for (Integer pageId : pageIdsResult.data()) {
            if (pageId == null || pageId < 0) {
                return failure(requestId, "INVALID_PAGE_ID", "表页列表包含非法页号", pageId);
            }
            DbResult<RowId> inserted = insertIntoExistingPage(requestId, pageId, schema, row);
            if (inserted.isOk()) {
                return inserted;
            }
            if (!"PAGE_FULL".equals(inserted.error().getCode())) {
                return inserted;
            }
        }
        return insertIntoNewPage(requestId, schema, row);
    }

    // 扫描表的全部数据页，只返回没有被逻辑删除的记录
    public DbResult<RowSet> scanRows(String requestId, TableSchema schema) {
        if (schema == null) {
            return failure(requestId, "INVALID_SCHEMA", "表结构不能为null", null);
        }
        DbResult<Void> schemaResult = recordCodec.validateSchema(schema);
        if (!schemaResult.isOk()) {
            return failureFrom(schemaResult.error(), requestId, null);
        }

        DbResult<List<Integer>> pageIdsResult = listTablePageIds(requestId, schema.getName());
        if (!pageIdsResult.isOk()) {
            return DbResult.fail(pageIdsResult.error());
        }

        List<InternalRow> rows = new ArrayList<>();
        for (Integer pageId : pageIdsResult.data()) {
            if (pageId == null || pageId < 0) {
                return failure(requestId, "INVALID_PAGE_ID", "表页列表包含非法页号", pageId);
            }
            DbResult<PageData> pageResult = pageStorageClient.getPage(requestId, pageId);
            if (!pageResult.isOk()) {
                return failureFrom(pageResult.error(), requestId, pageId);
            }
            PageData pageData = pageResult.data();
            if (pageData == null || pageData.getPageId() != pageId) {
                return failure(requestId, "PAGE_ID_MISMATCH", "页式存储系统返回了错误页号", pageId);
            }

            DbResult<DataPage> page = dataPageCodec.decode(pageData.getData());
            if (!page.isOk()) {
                return failureFrom(page.error(), requestId, pageId);
            }
            DbResult<List<InternalRow>> liveRows = dataPageCodec.readLiveRows(
                    page.data(),
                    schema,
                    pageId,
                    recordCodec
            );
            if (!liveRows.isOk()) {
                return failureFrom(liveRows.error(), requestId, pageId);
            }
            rows.addAll(liveRows.data());
        }
        return DbResult.ok(new RowSet(schema.getColumns(), rows));
    }

    // 更新且仅更新调用方指定的记录，并返回实际更新数量
    public DbResult<Integer> updateRows(
            String requestId,
            TableSchema schema,
            List<RowId> rowIds,
            Map<String, Object> assignments) {
        if (schema == null) {
            return failure(requestId, "INVALID_SCHEMA", "表结构不能为null", null);
        }
        DbResult<Void> schemaResult = recordCodec.validateSchema(schema);
        if (!schemaResult.isOk()) {
            return failureFrom(schemaResult.error(), requestId, null);
        }
        TableSchema registered = schemas.get(schema.getName());
        if (registered == null) {
            return failure(requestId, "TABLE_NOT_FOUND", "表结构不存在：" + schema.getName(), null);
        }
        if (!sameSchema(registered, schema)) {
            return failure(requestId, "TABLE_SCHEMA_CONFLICT", "表结构与已登记结构不一致：" + schema.getName(), null);
        }

        DbResult<Map<String, Object>> normalizedAssignments = normalizeAssignments(
                requestId,
                schema,
                assignments
        );
        if (!normalizedAssignments.isOk()) {
            return DbResult.fail(normalizedAssignments.error());
        }
        if (rowIds == null) {
            return failure(requestId, "INVALID_ROW_ID", "记录标识列表不能为null", null);
        }
        if (rowIds.isEmpty()) {
            return DbResult.ok(0);
        }

        DbResult<List<Integer>> pageIdsResult = listTablePageIds(requestId, schema.getName());
        if (!pageIdsResult.isOk()) {
            return DbResult.fail(pageIdsResult.error());
        }
        Set<Integer> tablePageIds = new HashSet<>(pageIdsResult.data());
        Set<RowId> uniqueRowIds = new LinkedHashSet<>();
        Map<Integer, List<RowId>> rowsByPage = new LinkedHashMap<>();
        for (RowId rowId : rowIds) {
            if (rowId == null) {
                return failure(requestId, "INVALID_ROW_ID", "记录标识不能为null", null);
            }
            if (!tablePageIds.contains(rowId.pageId())) {
                return failure(requestId, "ROW_NOT_IN_TABLE", "记录页不属于目标表", rowId.pageId());
            }
            if (uniqueRowIds.add(rowId)) {
                rowsByPage.computeIfAbsent(rowId.pageId(), ignored -> new ArrayList<>()).add(rowId);
            }
        }

        Map<Integer, String> encodedPages = new LinkedHashMap<>();
        int updatedCount = 0;
        for (Map.Entry<Integer, List<RowId>> entry : rowsByPage.entrySet()) {
            int pageId = entry.getKey();
            DbResult<PageData> pageResult = pageStorageClient.getPage(requestId, pageId);
            if (!pageResult.isOk()) {
                return failureFrom(pageResult.error(), requestId, pageId);
            }
            PageData pageData = pageResult.data();
            if (pageData == null || pageData.getPageId() != pageId) {
                return failure(requestId, "PAGE_ID_MISMATCH", "页式存储系统返回了错误页号", pageId);
            }
            DbResult<DataPage> decodedPage = dataPageCodec.decode(pageData.getData());
            if (!decodedPage.isOk()) {
                return failureFrom(decodedPage.error(), requestId, pageId);
            }

            List<PageSlot> updatedSlots = new ArrayList<>();
            for (PageSlot slot : decodedPage.data().slots()) {
                updatedSlots.add(new PageSlot(slot.getData(), slot.isDeleted()));
            }
            for (RowId rowId : entry.getValue()) {
                if (rowId.slotId() >= updatedSlots.size()) {
                    return failure(requestId, "ROW_NOT_FOUND", "记录槽不存在", pageId);
                }
                PageSlot slot = updatedSlots.get(rowId.slotId());
                if (slot.isDeleted()) {
                    return failure(requestId, "ROW_NOT_FOUND", "记录已经删除", pageId);
                }
                DbResult<Row> decodedRow = recordCodec.decode(schema, slot.getData());
                if (!decodedRow.isOk()) {
                    return failureFrom(decodedRow.error(), requestId, pageId);
                }

                Map<String, Object> updatedValues = new LinkedHashMap<>(decodedRow.data().values());
                updatedValues.putAll(normalizedAssignments.data());
                DbResult<byte[]> encodedRow = recordCodec.encode(schema, new Row(updatedValues));
                if (!encodedRow.isOk()) {
                    return failureFrom(encodedRow.error(), requestId, pageId);
                }
                updatedSlots.set(rowId.slotId(), new PageSlot(encodedRow.data(), false));
                updatedCount++;
            }

            DbResult<String> encodedPage = dataPageCodec.encode(new DataPage(updatedSlots));
            if (!encodedPage.isOk()) {
                return failureFrom(encodedPage.error(), requestId, pageId);
            }
            encodedPages.put(pageId, encodedPage.data());
        }

        for (Map.Entry<Integer, String> entry : encodedPages.entrySet()) {
            DbResult<?> written = pageStorageClient.writePage(
                    requestId,
                    entry.getKey(),
                    entry.getValue()
            );
            if (!written.isOk()) {
                return failureFrom(written.error(), requestId, entry.getKey());
            }
        }
        return DbResult.ok(updatedCount);
    }

    // 按物理位置读取一条尚未删除的记录
    public DbResult<InternalRow> readRow(
            String requestId,
            TableSchema schema,
            RowId rowId) {
        if (schema == null) {
            return failure(requestId, "INVALID_SCHEMA", "表结构不能为null", null);
        }
        DbResult<Void> schemaResult = recordCodec.validateSchema(schema);
        if (!schemaResult.isOk()) {
            return failureFrom(schemaResult.error(), requestId, null);
        }
        TableSchema registered = schemas.get(schema.getName());
        if (registered == null) {
            return failure(requestId, "TABLE_NOT_FOUND", "表结构不存在：" + schema.getName(), null);
        }
        if (!sameSchema(registered, schema)) {
            return failure(requestId, "TABLE_SCHEMA_CONFLICT", "表结构与已登记结构不一致：" + schema.getName(), null);
        }
        if (rowId == null) {
            return failure(requestId, "INVALID_ROW_ID", "记录标识不能为null", null);
        }

        DbResult<List<Integer>> pageIds = listTablePageIds(requestId, schema.getName());
        if (!pageIds.isOk()) {
            return DbResult.fail(pageIds.error());
        }
        if (!pageIds.data().contains(rowId.pageId())) {
            return failure(requestId, "ROW_NOT_IN_TABLE", "记录页不属于目标表", rowId.pageId());
        }
        DbResult<PageData> pageResult = pageStorageClient.getPage(requestId, rowId.pageId());
        if (!pageResult.isOk()) {
            return failureFrom(pageResult.error(), requestId, rowId.pageId());
        }
        PageData pageData = pageResult.data();
        if (pageData == null || pageData.getPageId() != rowId.pageId()) {
            return failure(requestId, "PAGE_ID_MISMATCH", "页式存储系统返回了错误页号", rowId.pageId());
        }
        DbResult<DataPage> decodedPage = dataPageCodec.decode(pageData.getData());
        if (!decodedPage.isOk()) {
            return failureFrom(decodedPage.error(), requestId, rowId.pageId());
        }
        List<PageSlot> slots = decodedPage.data().slots();
        if (rowId.slotId() >= slots.size() || slots.get(rowId.slotId()).isDeleted()) {
            return failure(requestId, "ROW_NOT_FOUND", "记录不存在或已经删除", rowId.pageId());
        }
        DbResult<Row> decodedRow = recordCodec.decode(schema, slots.get(rowId.slotId()).getData());
        if (!decodedRow.isOk()) {
            return failureFrom(decodedRow.error(), requestId, rowId.pageId());
        }
        return DbResult.ok(new InternalRow(rowId, decodedRow.data()));
    }

    // 检查当前存储引擎是否能够访问指定表的指定索引
    public DbResult<Void> ensureIndexAvailable(
            String requestId,
            String table,
            String index) {
        String normalizedTable = normalizeTable(table);
        String normalizedIndex = normalizeIndex(index);
        if (normalizedTable == null || normalizedTable.isBlank()
                || !schemas.containsKey(normalizedTable)) {
            return failure(requestId, "TABLE_NOT_FOUND", "表结构不存在：" + table, null);
        }
        if (normalizedIndex == null || normalizedIndex.isBlank()) {
            return failure(requestId, "INVALID_INDEX", "索引名称不能为空", null);
        }
        DbResult<Void> available = indexLookup.ensureAvailable(
                requestId,
                normalizedTable,
                normalizedIndex
        );
        if (available == null) {
            return failure(requestId, "INVALID_INDEX_RESPONSE", "底层索引未返回可用性结果", null);
        }
        return available.isOk()
                ? DbResult.ok(null)
                : failureFrom(available.error(), requestId, null);
    }

    // 向底层索引能力请求满足条件的记录物理位置
    public DbResult<List<RowId>> lookupIndex(
            String requestId,
            String table,
            String index,
            Object condition) {
        String normalizedTable = normalizeTable(table);
        String normalizedIndex = normalizeIndex(index);
        if (normalizedTable == null || normalizedTable.isBlank()
                || !schemas.containsKey(normalizedTable)) {
            return failure(requestId, "TABLE_NOT_FOUND", "表结构不存在：" + table, null);
        }
        if (normalizedIndex == null || normalizedIndex.isBlank()) {
            return failure(requestId, "INVALID_INDEX", "索引名称不能为空", null);
        }
        if (condition == null) {
            return failure(requestId, "INVALID_INDEX_CONDITION", "索引查询条件不能为null", null);
        }

        DbResult<List<RowId>> found = indexLookup.find(
                requestId,
                normalizedTable,
                normalizedIndex,
                condition
        );
        if (found == null) {
            return failure(requestId, "INVALID_INDEX_RESPONSE", "底层索引未返回查询结果", null);
        }
        if (!found.isOk()) {
            return failureFrom(found.error(), requestId, null);
        }
        if (found.data() == null || found.data().stream().anyMatch(Objects::isNull)) {
            return failure(requestId, "INVALID_INDEX_RESPONSE", "底层索引返回了无效记录位置", null);
        }
        return DbResult.ok(List.copyOf(found.data()));
    }

    // 只标记调用方提供的记录位置，不影响同表其他记录
    public DbResult<Integer> deleteRows(String requestId, String table, List<RowId> rowIds) {
        String normalizedTable = normalizeTable(table);
        if (normalizedTable == null || !schemas.containsKey(normalizedTable)) {
            return failure(requestId, "TABLE_NOT_FOUND", "表结构不存在：" + table, null);
        }
        if (rowIds == null) {
            return failure(requestId, "INVALID_ROW_ID", "记录标识列表不能为null", null);
        }
        if (rowIds.isEmpty()) {
            return DbResult.ok(0);
        }

        DbResult<List<Integer>> pageIdsResult = listTablePageIds(requestId, normalizedTable);
        if (!pageIdsResult.isOk()) {
            return DbResult.fail(pageIdsResult.error());
        }
        Set<Integer> tablePageIds = new HashSet<>(pageIdsResult.data());
        Map<Integer, List<RowId>> rowsByPage = new LinkedHashMap<>();
        for (RowId rowId : rowIds) {
            if (rowId == null) {
                return failure(requestId, "INVALID_ROW_ID", "记录标识不能为null", null);
            }
            if (!tablePageIds.contains(rowId.pageId())) {
                return failure(requestId, "ROW_NOT_IN_TABLE", "记录页不属于目标表", rowId.pageId());
            }
            rowsByPage.computeIfAbsent(rowId.pageId(), ignored -> new ArrayList<>()).add(rowId);
        }

        int deletedCount = 0;
        for (Map.Entry<Integer, List<RowId>> entry : rowsByPage.entrySet()) {
            int pageId = entry.getKey();
            DbResult<PageData> pageResult = pageStorageClient.getPage(requestId, pageId);
            if (!pageResult.isOk()) {
                return failureFrom(pageResult.error(), requestId, pageId);
            }
            PageData pageData = pageResult.data();
            if (pageData == null || pageData.getPageId() != pageId) {
                return failure(requestId, "PAGE_ID_MISMATCH", "页式存储系统返回了错误页号", pageId);
            }
            DbResult<DataPage> page = dataPageCodec.decode(pageData.getData());
            if (!page.isOk()) {
                return failureFrom(page.error(), requestId, pageId);
            }
            DbResult<Integer> marked = dataPageCodec.markDeleted(page.data(), entry.getValue(), pageId);
            if (!marked.isOk()) {
                return failureFrom(marked.error(), requestId, pageId);
            }
            if (marked.data() == 0) {
                continue;
            }

            DbResult<String> encodedPage = dataPageCodec.encode(page.data());
            if (!encodedPage.isOk()) {
                return failureFrom(encodedPage.error(), requestId, pageId);
            }
            DbResult<?> writeResult = pageStorageClient.writePage(requestId, pageId, encodedPage.data());
            if (!writeResult.isOk()) {
                return failureFrom(writeResult.error(), requestId, pageId);
            }
            deletedCount += marked.data();
        }
        return DbResult.ok(deletedCount);
    }

    // 删除表页映射，供建表后目录登记失败时进行补偿
    public DbResult<Void> dropTableStorage(String requestId, String table) {
        String normalizedTable = normalizeTable(table);
        if (normalizedTable == null) {
            return failure(requestId, "TABLE_NOT_FOUND", "表名不能为null", null);
        }
        DbResult<DropTablePagesResult> dropped = pageStorageClient.dropTablePages(requestId, normalizedTable);
        if (!dropped.isOk()) {
            return failureFrom(dropped.error(), requestId, null);
        }
        if (dropped.data() == null || !dropped.data().isRemoved()) {
            return failure(requestId, "TABLE_NOT_FOUND", "页式存储系统未删除表页映射", null);
        }
        schemas.remove(normalizedTable);
        return DbResult.ok(null);
    }

    // 返回页式存储系统维护的表页列表
    public DbResult<List<Integer>> listTablePageIds(String requestId, String table) {
        String normalizedTable = normalizeTable(table);
        if (normalizedTable == null) {
            return failure(requestId, "TABLE_NOT_FOUND", "表名不能为null", null);
        }
        DbResult<TablePages> listed = pageStorageClient.listTablePages(requestId, normalizedTable);
        if (!listed.isOk()) {
            return failureFrom(listed.error(), requestId, null);
        }
        if (listed.data() == null) {
            return failure(requestId, "INVALID_STORAGE_RESPONSE", "页式存储系统未返回表页列表", null);
        }
        List<Integer> pageIds = listed.data().getPageIds();
        for (Integer pageId : pageIds) {
            if (pageId == null || pageId < 0) {
                return failure(requestId, "INVALID_PAGE_ID", "页式存储系统返回了非法页号", pageId);
            }
        }
        return DbResult.ok(List.copyOf(pageIds));
    }

    // 尝试把一条记录追加到指定的已有页
    public DbResult<RowId> insertIntoExistingPage(
            String requestId,
            int pageId,
            TableSchema schema,
            Row row) {
        if (pageId < 0) {
            return failure(requestId, "INVALID_PAGE_ID", "页号不能为负数", pageId);
        }
        DbResult<byte[]> encodedRow = recordCodec.encode(schema, row);
        if (!encodedRow.isOk()) {
            return failureFrom(encodedRow.error(), requestId, pageId);
        }
        if (!new DataPage().canFit(encodedRow.data().length)) {
            return failure(requestId, "RECORD_TOO_LARGE", "记录无法放入一页", pageId);
        }

        DbResult<PageData> pageResult = pageStorageClient.getPage(requestId, pageId);
        if (!pageResult.isOk()) {
            return failureFrom(pageResult.error(), requestId, pageId);
        }
        PageData pageData = pageResult.data();
        if (pageData == null || pageData.getPageId() != pageId) {
            return failure(requestId, "PAGE_ID_MISMATCH", "页式存储系统返回了错误页号", pageId);
        }
        DbResult<DataPage> decodedPage = dataPageCodec.decode(pageData.getData());
        if (!decodedPage.isOk()) {
            return failureFrom(decodedPage.error(), requestId, pageId);
        }
        DbResult<RowId> appended = dataPageCodec.append(decodedPage.data(), encodedRow.data(), pageId);
        if (!appended.isOk()) {
            return failureFrom(appended.error(), requestId, pageId);
        }
        DbResult<String> encodedPage = dataPageCodec.encode(decodedPage.data());
        if (!encodedPage.isOk()) {
            return failureFrom(encodedPage.error(), requestId, pageId);
        }
        DbResult<?> writeResult = pageStorageClient.writePage(requestId, pageId, encodedPage.data());
        if (!writeResult.isOk()) {
            return failureFrom(writeResult.error(), requestId, pageId);
        }
        return appended;
    }

    // 分配新页并把一条记录写入新页
    public DbResult<RowId> insertIntoNewPage(
            String requestId,
            TableSchema schema,
            Row row) {
        if (schema == null) {
            return failure(requestId, "INVALID_SCHEMA", "表结构不能为null", null);
        }
        DbResult<byte[]> encodedRow = recordCodec.encode(schema, row);
        if (!encodedRow.isOk()) {
            return failureFrom(encodedRow.error(), requestId, null);
        }
        DataPage page = new DataPage();
        if (!page.canFit(encodedRow.data().length)) {
            return failure(requestId, "RECORD_TOO_LARGE", "记录无法放入一页", null);
        }

        DbResult<AllocatedPage> allocation = pageStorageClient.allocatePageForTable(
                requestId,
                schema.getName()
        );
        if (!allocation.isOk()) {
            return failureFrom(allocation.error(), requestId, null);
        }
        if (allocation.data() == null || allocation.data().getPageId() < 0) {
            return failure(requestId, "INVALID_STORAGE_RESPONSE", "页式存储系统未返回合法新页", null);
        }
        int pageId = allocation.data().getPageId();
        DbResult<RowId> appended = dataPageCodec.append(page, encodedRow.data(), pageId);
        if (!appended.isOk()) {
            return failureFrom(appended.error(), requestId, pageId);
        }
        DbResult<String> encodedPage = dataPageCodec.encode(page);
        if (!encodedPage.isOk()) {
            return failureFrom(encodedPage.error(), requestId, pageId);
        }
        DbResult<?> writeResult = pageStorageClient.writePage(requestId, pageId, encodedPage.data());
        if (!writeResult.isOk()) {
            return failureFrom(writeResult.error(), requestId, pageId);
        }
        return appended;
    }

    // 规范化表名，接口比较时不区分大小写
    private String normalizeTable(String table) {
        return table == null ? null : table.toLowerCase(Locale.ROOT);
    }

    // 规范化索引名称，接口比较时不区分大小写
    private String normalizeIndex(String index) {
        return index == null ? null : index.toLowerCase(Locale.ROOT);
    }

    // 比较两份表结构是否具有相同的列顺序和列类型
    private boolean sameSchema(TableSchema left, TableSchema right) {
        if (!left.getName().equals(right.getName())
                || left.getColumns().size() != right.getColumns().size()) {
            return false;
        }
        for (int index = 0; index < left.getColumns().size(); index++) {
            ColumnSchema leftColumn = left.getColumns().get(index);
            ColumnSchema rightColumn = right.getColumns().get(index);
            if (!leftColumn.getName().equals(rightColumn.getName())
                    || !leftColumn.getDataType().equals(rightColumn.getDataType())) {
                return false;
            }
        }
        return true;
    }

    // 校验赋值列和值，并把列名规范化为小写
    private DbResult<Map<String, Object>> normalizeAssignments(
            String requestId,
            TableSchema schema,
            Map<String, Object> assignments) {
        if (assignments == null || assignments.isEmpty()) {
            return failure(requestId, "INVALID_ASSIGNMENT", "更新赋值不能为空", null);
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : assignments.entrySet()) {
            String rawName = entry.getKey();
            if (rawName == null || rawName.isBlank()) {
                return failure(requestId, "INVALID_ASSIGNMENT", "更新列名不能为空", null);
            }
            String name = rawName.toLowerCase(Locale.ROOT);
            if (normalized.containsKey(name)) {
                return failure(requestId, "INVALID_ASSIGNMENT", "更新列名重复：" + name, null);
            }
            if (schema.findColumn(name) == null) {
                return failure(requestId, "ROW_SCHEMA_MISMATCH", "更新引用了未知列：" + name, null);
            }
            if (entry.getValue() == null) {
                return failure(requestId, "ROW_VALUE_NULL", "更新值不能为null：" + name, null);
            }
            normalized.put(name, entry.getValue());
        }

        Map<String, Object> probeValues = new LinkedHashMap<>();
        for (ColumnSchema column : schema.getColumns()) {
            probeValues.put(column.getName(), "INT".equals(column.getDataType()) ? 0 : "");
        }
        probeValues.putAll(normalized);
        DbResult<Void> valueValidation = recordCodec.validateRow(schema, new Row(probeValues));
        if (!valueValidation.isOk()) {
            return failureFrom(valueValidation.error(), requestId, null);
        }
        return DbResult.ok(Map.copyOf(normalized));
    }

    // 创建统一格式的存储引擎错误
    private <T> DbResult<T> failure(
            String requestId,
            String code,
            String message,
            Integer pageId) {
        return DbResult.fail(new DbError(
                requestId,
                null,
                "STORAGE",
                code,
                message,
                null,
                null,
                pageId
        ));
    }

    // 将下层错误补全当前请求和页号后继续向上返回
    private <T> DbResult<T> failureFrom(DbError error, String requestId, Integer pageId) {
        if (error == null) {
            return failure(requestId, "INVALID_DEPENDENCY_ERROR", "下层组件未返回错误信息", pageId);
        }
        Integer effectivePageId = pageId != null ? pageId : error.getPageId();
        return DbResult.fail(new DbError(
                requestId,
                error.getStatementIndex(),
                error.getStage(),
                error.getCode(),
                error.getMessage(),
                error.getLine(),
                error.getColumn(),
                effectivePageId
        ));
    }

    // 在没有接入物理索引模块时稳定返回INDEX_UNAVAILABLE
    private static final class UnavailableIndexLookup implements IndexLookup {

        // 未配置索引能力时不能执行索引查找
        @Override
        public DbResult<List<RowId>> find(
                String requestId,
                String table,
                String index,
                Object condition) {
            return unavailable(requestId, index);
        }

        // 未配置索引能力时任何索引都不可用
        @Override
        public DbResult<Void> ensureAvailable(String requestId, String table, String index) {
            return unavailable(requestId, index);
        }

        // 创建统一的索引不可用错误
        private <T> DbResult<T> unavailable(String requestId, String index) {
            return DbResult.fail(new DbError(
                    requestId,
                    null,
                    "STORAGE",
                    "INDEX_UNAVAILABLE",
                    "索引不可用：" + index,
                    null,
                    null,
                    null
            ));
        }
    }
}
