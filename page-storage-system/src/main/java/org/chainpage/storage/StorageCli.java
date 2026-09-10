package org.chainpage.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

public final class StorageCli {
    public static void main(String[] args)throws Exception{Path root=Path.of("storage-data");int capacity=16;String policy="LRU";for(int i=0;i<args.length;i++){switch(args[i]){case"--root"->root=Path.of(args[++i]);case"--capacity"->capacity=Integer.parseInt(args[++i]);case"--policy"->policy=args[++i];default->throw new IllegalArgumentException(args[i]);}}try(StorageManager s=new StorageManager(root,capacity,policy,true);BufferedReader in=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));Writer out=new OutputStreamWriter(System.out,StandardCharsets.UTF_8)){String line;while((line=in.readLine())!=null){if(line.isBlank())continue;Map<String,Object> response;try{Map<String,Object> request=JsonFiles.JSON.readValue(line,new TypeReference<>(){});response=handle(s,request);}catch(StorageException e){response=error(e,null);}catch(Exception e){response=error(new StorageException("INVALID_JSON",e.getMessage()),null);}out.write(JsonFiles.compact(response));out.write('\n');out.flush();}}}
    public static Map<String,Object> handle(StorageManager s,Map<String,Object> q){String rid=q.get("requestId") instanceof String x?x:null;try{if(q.containsKey("requestId")&&(rid==null||rid.isEmpty()))throw new StorageException("INVALID_REQUEST","requestId 必须是非空字符串");String op=str(q,"op");Map<String,Object>d=JsonFiles.map();if(rid!=null)d.put("requestId",rid);switch(op){
        case"allocate_page"->{fields(q,"requestId","op");d.put("pageId",s.allocatePage());}
        case"free_page"->{fields(q,"requestId","op","pageId");int p=integer(q,"pageId");s.freePage(p);d.put("pageId",p);d.put("freed",true);}
        case"read_at","read_page"->{fields(q,"requestId","op","pageId");int p=integer(q,"pageId");d.put("pageId",p);d.put("data",JsonFiles.b64(s.readPageDirect(p)));}
        case"write_at"->{fields(q,"requestId","op","pageId","data");int p=integer(q,"pageId");s.writePageDirect(p,page(q,"data",p));d.put("pageId",p);d.put("written",4096);}
        case"sync"->{fields(q,"requestId","op");s.sync();d.put("flushed",true);}
        case"get_page"->{fields(q,"requestId","op","pageId","owner");int p=integer(q,"pageId");BufferPool.Page x=s.getPage(p,optStr(q,"owner"));d.put("pageId",p);d.put("data",JsonFiles.b64(x.data()));d.put("hit",x.hit());d.put("dirty",x.dirty());}
        case"write_page"->{fields(q,"requestId","op","pageId","data","owner","txId");int p=integer(q,"pageId");byte[] data=page(q,"data",p);s.writePage(p,data,optStr(q,"owner"),optInt(q,"txId",0));d.put("pageId",p);d.put("dirty",true);}
        case"put_page"->{fields(q,"requestId","op","pageId","data","dirty","owner","txId");int p=integer(q,"pageId");Object dirty=q.get("dirty");if(!(dirty instanceof Boolean flag))throw new StorageException("INVALID_REQUEST","dirty 必须为布尔值");BufferPool.Put x=s.putPage(p,page(q,"data",p),flag,optStr(q,"owner"),optInt(q,"txId",0));d.put("pageId",p);d.put("evicted",x.evicted());d.put("flushedPageId",x.flushedPageId());}
        case"flush_page"->{fields(q,"requestId","op","pageId","owner");int p=integer(q,"pageId");s.flushPage(p,optStr(q,"owner"));d.put("pageId",p);d.put("flushed",true);}
        case"flush_all"->{fields(q,"requestId","op");d.put("flushedPageIds",s.flushAll());}
        case"storage_stats","stats"->{fields(q,"requestId","op");d.putAll(s.stats());}
        case"set_policy"->{fields(q,"requestId","op","policy");d.putAll(s.setPolicy(str(q,"policy")));}
        case"buffer_events"->{fields(q,"requestId","op","clear");Object clear=q.getOrDefault("clear",false);if(!(clear instanceof Boolean flag))throw new StorageException("INVALID_REQUEST","clear 必须为布尔值");d.put("events",s.events(flag));}
        case"record_insert"->{fields(q,"requestId","op","pageId");d.putAll(s.policyInsert(integer(q,"pageId")));}
        case"record_access"->{fields(q,"requestId","op","pageId");d.putAll(s.policyAccess(integer(q,"pageId")));}
        case"choose_victim"->{fields(q,"requestId","op","residentPageIds");d.put("pageId",s.policyVictim(integerSet(q,"residentPageIds")));}
        case"create_table_pages"->{fields(q,"requestId","op","table");String t=TablePageMap.normalize(str(q,"table"));d.put("table",t);d.put("pageIds",s.createTablePages(t));}
        case"append_page"->{fields(q,"requestId","op","table","pageId");String t=TablePageMap.normalize(str(q,"table"));d.put("table",t);d.put("pageIds",s.appendTablePage(t,integer(q,"pageId")));}
        case"list_pages"->{fields(q,"requestId","op","table");String t=TablePageMap.normalize(str(q,"table"));d.put("table",t);d.put("pageIds",s.listTablePages(t));}
        case"allocate_page_for_table"->{fields(q,"requestId","op","table");String t=TablePageMap.normalize(str(q,"table"));List<Integer> ids=s.allocatePageForTable(t);d.put("table",t);d.put("pageId",ids.get(ids.size()-1));d.put("pageIds",ids);}
        case"list_table_pages"->{fields(q,"requestId","op","table");String t=TablePageMap.normalize(str(q,"table"));d.put("table",t);d.put("pageIds",s.listTablePages(t));}
        case"drop_table_pages"->{fields(q,"requestId","op","table");String t=TablePageMap.normalize(str(q,"table"));d.put("table",t);d.put("removed",true);d.put("freedPageIds",s.dropTablePages(t));}
        case"insert_record"->{fields(q,"requestId","op","pageId","row","owner","txId");d.putAll(s.insertRecord(integer(q,"pageId"),map(q,"row"),optStr(q,"owner"),optInt(q,"txId",0)));}
        case"read_record"->{fields(q,"requestId","op","pageId","slotId","owner");d.putAll(s.readRecord(integer(q,"pageId"),integer(q,"slotId"),optStr(q,"owner")));}
        case"delete_record"->{fields(q,"requestId","op","pageId","slotId","owner","txId");d.putAll(s.deleteRecord(integer(q,"pageId"),integer(q,"slotId"),optStr(q,"owner"),optInt(q,"txId",0)));}
        case"delete_rows"->{fields(q,"requestId","op","table","rowIds","owner","txId");d.put("deleted",s.deleteRows(TablePageMap.normalize(str(q,"table")),mapList(q,"rowIds"),optStr(q,"owner"),optInt(q,"txId",0)));}
        case"scan_records"->{fields(q,"requestId","op","pageId","owner");d.put("pageId",integer(q,"pageId"));d.put("records",s.scanRecords(integer(q,"pageId"),optStr(q,"owner")));}
        case"create_index"->{fields(q,"requestId","op","indexId","unique","keyType");Object u=q.getOrDefault("unique",true);if(!(u instanceof Boolean unique))throw new StorageException("INVALID_REQUEST","unique 必须为布尔值");d.putAll(s.createIndex(integer(q,"indexId"),unique,optStr(q,"keyType")));}
        case"drop_index"->{fields(q,"requestId","op","indexId");d.putAll(s.dropIndex(integer(q,"indexId")));}
        case"index_insert"->{fields(q,"requestId","op","indexId","key","rowId");d.putAll(s.indexInsert(integer(q,"indexId"),q.get("key"),map(q,"rowId")));}
        case"index_delete"->{fields(q,"requestId","op","indexId","key","rowId");d.putAll(s.indexDelete(integer(q,"indexId"),q.get("key"),q.get("rowId")==null?null:map(q,"rowId")));}
        case"index_search"->{fields(q,"requestId","op","indexId","key");d.put("rowIds",s.indexSearch(integer(q,"indexId"),q.get("key")));}
        case"index_range"->{fields(q,"requestId","op","indexId","start","end");d.put("rowIds",s.indexRange(integer(q,"indexId"),q.get("start"),q.get("end")));}
        case"validate_index"->{fields(q,"requestId","op","indexId");d.putAll(s.validateIndex(integer(q,"indexId")));}
        case"append_log"->{fields(q,"requestId","op","txId","pageId","before","after");int p=integer(q,"pageId");d.put("logSeq",s.appendLog(integer(q,"txId"),p,page(q,"before",p),page(q,"after",p)));d.put("durable",true);}
        case"recover"->{fields(q,"requestId","op");d.putAll(s.recover());}
        case"lock_page"->{fields(q,"requestId","op","pageId","mode","owner");d.putAll(s.lockPage(integer(q,"pageId"),str(q,"mode"),str(q,"owner")));}
        case"unlock_page"->{fields(q,"requestId","op","pageId","owner");d.putAll(s.unlockPage(integer(q,"pageId"),str(q,"owner")));}
        default->throw new StorageException("UNSUPPORTED_OPERATION","不支持的操作: "+op);}
        return Map.of("ok",true,"data",d);
    }catch(StorageException e){return error(e,rid);}catch(Exception e){return error(new StorageException("STORAGE_INTERNAL_ERROR",e.getMessage(),null,e),rid);}}
    private static Map<String,Object> error(StorageException e,String id){return Map.of("ok",false,"error",e.error(id));}
    private static void fields(Map<String,Object>q,String...allowed){Set<String>x=new HashSet<>(q.keySet());x.removeAll(Set.of(allowed));if(!x.isEmpty())throw new StorageException("INVALID_REQUEST","请求包含未定义字段: "+x);}
    private static String str(Map<String,Object>q,String k){Object v=q.get(k);if(!(v instanceof String s)||s.isEmpty())throw new StorageException("INVALID_REQUEST",k+" 必须是非空字符串");return s;}private static String optStr(Map<String,Object>q,String k){return q.get(k)==null?null:str(q,k);}private static int integer(Map<String,Object>q,String k){Object v=q.get(k);if(!(v instanceof Integer i)||i<0)throw new StorageException("INVALID_PAGE_ID",k+" 必须是非负整数");return i;}private static int optInt(Map<String,Object>q,String k,int d){return q.containsKey(k)?integer(q,k):d;}private static byte[] page(Map<String,Object>q,String k,int id){byte[]b=JsonFiles.unb64(str(q,k),"INVALID_PAGE_DATA");FileManager.page(b,id);return b;}private static Set<Integer> integerSet(Map<String,Object>q,String k){Object value=q.get(k);if(!(value instanceof List<?> list)||list.isEmpty())throw new StorageException("BUFFER_POLICY_INVALID_STATE","候选页集合不能为空");Set<Integer> out=new LinkedHashSet<>();for(Object v:list)if(!(v instanceof Integer i)||i<0||!out.add(i))throw new StorageException("BUFFER_POLICY_INVALID_STATE","候选页必须是无重复的非负整数");return out;}@SuppressWarnings("unchecked")private static Map<String,Object>map(Map<String,Object>q,String k){Object v=q.get(k);if(!(v instanceof Map<?,?>))throw new StorageException("INVALID_REQUEST",k+" 必须是对象");return(Map<String,Object>)v;}@SuppressWarnings("unchecked")private static List<Map<String,Object>>mapList(Map<String,Object>q,String k){Object value=q.get(k);if(!(value instanceof List<?> list))throw new StorageException("INVALID_REQUEST",k+" 必须是数组");List<Map<String,Object>> out=new ArrayList<>();for(Object item:list){if(!(item instanceof Map<?,?>))throw new StorageException("INVALID_REQUEST",k+" 必须只包含对象");out.add((Map<String,Object>)item);}return out;}
}
