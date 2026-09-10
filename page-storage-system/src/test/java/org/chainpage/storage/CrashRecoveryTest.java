package org.chainpage.storage;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class CrashRecoveryTest {
    @TempDir Path dir;
    static Map<String,Object> rid(int n){return Map.of("pageId",n,"slotId",0);}
    static Stream<org.junit.jupiter.params.provider.Arguments> points(){return Stream.of(
        org.junit.jupiter.params.provider.Arguments.of("insert","journal_durable",1),
        org.junit.jupiter.params.provider.Arguments.of("insert","page_allocate",1),
        org.junit.jupiter.params.provider.Arguments.of("insert","index_page_write",1),
        org.junit.jupiter.params.provider.Arguments.of("insert","index_page_write",2),
        org.junit.jupiter.params.provider.Arguments.of("insert","before_commit",1),
        org.junit.jupiter.params.provider.Arguments.of("delete","page_free",1),
        org.junit.jupiter.params.provider.Arguments.of("delete","before_commit",1),
        org.junit.jupiter.params.provider.Arguments.of("allocate-table","page_allocate",1),
        org.junit.jupiter.params.provider.Arguments.of("drop-table","page_free",1),
        org.junit.jupiter.params.provider.Arguments.of("drop-table","page_free",2));}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("points")
    void processDeathRollsBackWholeOperation(String action,String point,int count)throws Exception{
        prepare();Map<String,byte[]> before=snapshot();Process p=spawn(action,point,count);assertEquals(61,p.waitFor());assertTrue(Files.exists(dir.resolve("operation.undo")));
        try(StorageManager recovered=new StorageManager(dir)){assertTrue(recovered.recoveredOperation());}
        Map<String,byte[]> after=snapshot();before.forEach((k,v)->assertArrayEquals(v,after.get(k),k));assertFalse(Files.exists(dir.resolve("operation.undo")));
        try(StorageManager clean=new StorageManager(dir)){assertFalse(clean.recoveredOperation());}
    }
    @Test void recoveryIsIdempotentAfterSecondCrash()throws Exception{prepare();Map<String,byte[]> before=snapshot();Process first=spawn("insert","before_commit",1);assertEquals(61,first.waitFor());Process second=spawn("open","during_restore",1);assertEquals(61,second.waitFor());assertTrue(Files.exists(dir.resolve("operation.undo")));try(StorageManager recovered=new StorageManager(dir)){assertTrue(recovered.recoveredOperation());}Map<String,byte[]> after=snapshot();before.forEach((k,v)->assertArrayEquals(v,after.get(k),k));}
    private void prepare(){try(StorageManager s=new StorageManager(dir,4,"LRU",true)){s.createIndex(1,true,"INT");for(int i=0;i<16;i++)s.indexInsert(1,i,rid(i));s.createTablePages("t");for(int i=0;i<2;i++){int p=s.allocatePageForTable("t").get(i);s.writePage(p,new byte[4096],null,0);}s.flushAll();}}
    private Map<String,byte[]> snapshot()throws Exception{Map<String,byte[]>m=new LinkedHashMap<>();for(String n:List.of("pages.dat","page_allocation.json","table_pages.json","indexes.json","wal.log"))m.put(n,Files.readAllBytes(dir.resolve(n)));return m;}
    private Process spawn(String action,String point,int count)throws Exception{ProcessBuilder p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),CrashHarness.class.getName(),dir.toString(),action);p.environment().put("CHAINPAGE_CRASH_POINT",point);p.environment().put("CHAINPAGE_CRASH_COUNT",Integer.toString(count));p.redirectErrorStream(true);return p.start();}
}
