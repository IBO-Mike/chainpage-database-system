package edu.csu.chainpage.engine.contract;

import edu.csu.chainpage.engine.common.DbResult;

// 访问页式存储系统的接口
public interface PageStorageClient {
    // 读取一个已经分配的页
    DbResult<PageData> getPage(String requestId, int pageId);
    // 把完整内容写入指定页
    DbResult<WritePageResult> writePage(String requestId, int pageId, String base64Data);
    // 为新表创建一个空的表页映射
    DbResult<TablePages> createTablePages(String requestId, String table);
    // 删除页表映射，释放该表的所有数据页
    DbResult<DropTablePagesResult> dropTablePages(String requestId, String table);
    // 为指定表分配一个新页，并同时更新表页映射
    DbResult<AllocatedPage> allocatePageForTable(String requestId, String table);
    // 取得某张表当前所有数据页的页号
    DbResult<TablePages> listTablePages(String requestId, String table);
    // 将所有脏页写回持久化存储
    DbResult<FlushResult> flushAll(String requestId);
}
