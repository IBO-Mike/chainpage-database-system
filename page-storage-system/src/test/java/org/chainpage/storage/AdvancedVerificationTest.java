package org.chainpage.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AdvancedVerificationTest {
    @TempDir Path dir;
    static byte[] page(int value) { byte[] b=new byte[4096]; Arrays.fill(b,(byte)value); return b; }
    static Map<String,Object> rid(int value) { return Map.of("pageId",value,"slotId",0); }

    @Test void standaloneJsonContractsAndStrictFields() {
        try (StorageManager s=new StorageManager(dir,2,"LRU",true)) {
            Map<String,Object> allocated=StorageCli.handle(s,Map.of("requestId","原样-1","op","allocate_page"));
            assertEquals("原样-1",data(allocated).get("requestId"));
            int p=(Integer)data(allocated).get("pageId");
            String encoded=Base64.getEncoder().encodeToString(page(19));
            assertEquals(4096,data(StorageCli.handle(s,Map.of("op","write_at","pageId",p,"data",encoded))).get("written"));
            assertEquals(encoded,data(StorageCli.handle(s,Map.of("op","read_at","pageId",p))).get("data"));
            assertEquals(true,data(StorageCli.handle(s,Map.of("op","sync"))).get("flushed"));
            assertEquals(encoded,data(StorageCli.handle(s,Map.of("op","read_page","pageId",p))).get("data"));
            Map<String,Object> invalid=StorageCli.handle(s,Map.of("requestId","bad","op","sync","extra",1));
            assertEquals(false,invalid.get("ok"));
            Map<?,?> error=(Map<?,?>)invalid.get("error");
            assertEquals(List.of("requestId","statementIndex","stage","code","message","line","column","pageId"),new ArrayList<>(error.keySet()));
            assertEquals("bad",error.get("requestId")); assertEquals("INVALID_REQUEST",error.get("code"));
            assertNull(error.get("statementIndex")); assertNull(error.get("line")); assertNull(error.get("column")); assertNull(error.get("pageId"));
        }
    }

    @Test void fifoAndLruChooseDifferentVictimsAndValidateCandidates() {
        try (StorageManager fifo=new StorageManager(dir.resolve("fifo"),2,"FIFO",true)) {
            int a=fifo.allocatePage(),b=fifo.allocatePage(),c=fifo.allocatePage();fifo.getPage(a,null);fifo.getPage(b,null);fifo.getPage(a,null);fifo.getPage(c,null);
            assertEquals(a,lastVictim(fifo.events(false)));
        }
        try (StorageManager lru=new StorageManager(dir.resolve("lru"),2,"LRU",true)) {
            int a=lru.allocatePage(),b=lru.allocatePage(),c=lru.allocatePage();lru.getPage(a,null);lru.getPage(b,null);lru.getPage(a,null);lru.getPage(c,null);
            assertEquals(b,lastVictim(lru.events(false)));
            StorageCli.handle(lru,Map.of("op","record_insert","pageId",8));StorageCli.handle(lru,Map.of("op","record_insert","pageId",9));StorageCli.handle(lru,Map.of("op","record_access","pageId",8));
            assertEquals(9,data(StorageCli.handle(lru,Map.of("op","choose_victim","residentPageIds",List.of(8,9)))).get("pageId"));
            assertEquals(false,StorageCli.handle(lru,Map.of("op","choose_victim","residentPageIds",List.of(8,8))).get("ok"));
        }
    }

    @Test void flushAndFreeHonorExternalPageLocks() {
        try (StorageManager s=new StorageManager(dir)) {
            int p=s.allocatePage();s.writePage(p,page(7),null,0);s.lockPage(p,"READ","reader");
            assertEquals("PAGE_LOCK_BUSY",assertThrows(StorageException.class,s::flushAll).code());
            assertEquals("PAGE_LOCK_BUSY",assertThrows(StorageException.class,()->s.freePage(p)).code());
            assertArrayEquals(page(0),s.readPageDirect(p));
            s.unlockPage(p,"reader");s.flushAll();assertArrayEquals(page(7),s.readPageDirect(p));s.freePage(p);
            assertEquals("PAGE_NOT_ALLOCATED",assertThrows(StorageException.class,()->s.readPageDirect(p)).code());
        }
    }

    @Test void writeLockBlocksReadAndReadLocksBlockWrite() {
        try (StorageManager s=new StorageManager(dir)) {
            int p=s.allocatePage();assertEquals(true,s.lockPage(p,"WRITE","writer").get("granted"));
            assertEquals(Map.of("granted",false,"wait",true),s.lockPage(p,"READ","reader"));s.unlockPage(p,"writer");
            s.lockPage(p,"READ","a");s.lockPage(p,"READ","b");assertEquals("PAGE_LOCK_BUSY",assertThrows(StorageException.class,()->s.writePage(p,page(2),"writer",0)).code());assertEquals(false,s.lockPage(p,"WRITE","writer").get("granted"));
            assertEquals("LOCK_NOT_OWNER",assertThrows(StorageException.class,()->s.unlockPage(p,"stranger")).code());s.unlockPage(p,"a");s.unlockPage(p,"b");
        }
    }

    @Test void referencedPagesCannotBeFreedOrSharedByTables() {
        try (StorageManager s=new StorageManager(dir)) {
            s.createTablePages("a");int p=s.allocatePageForTable("a").get(0);assertEquals("PAGE_IN_USE",assertThrows(StorageException.class,()->s.freePage(p)).code());
            s.createTablePages("b");assertEquals("STORAGE_TABLE_EXISTS",assertThrows(StorageException.class,()->s.appendTablePage("b",p)).code());
            Map<String,Object> index=s.createIndex(4,true,"INT");int root=(Integer)index.get("rootPageId");assertEquals("PAGE_IN_USE",assertThrows(StorageException.class,()->s.freePage(root)).code());
        }
    }

    @Test void slottedSpaceDeletedSlotAndRowTypes() {
        try (StorageManager s=new StorageManager(dir)) {
            int p=s.allocatePage();Map<String,Object> a=s.insertRecord(p,Map.of("id",1,"name","one"),null,0);int free=(Integer)a.get("freeBytes");
            Map<String,Object> b=s.insertRecord(p,Map.of("id",2,"name","two"),null,0);assertTrue((Integer)b.get("freeBytes")<free);
            s.deleteRecord(p,0,null,0);Map<String,Object> reused=s.insertRecord(p,Map.of("id",3,"name","three"),null,0);assertEquals(0,reused.get("slotId"));
            assertEquals("PAGE_NO_SPACE",assertThrows(StorageException.class,()->s.insertRecord(p,Map.of("x","z".repeat(5000)),null,0)).code());
            assertEquals("ROW_INVALID",assertThrows(StorageException.class,()->s.insertRecord(p,Map.of("x",true),null,0)).code());
        }
    }

    @Test void batchDeleteIsExactAndAtomic() {
        try (StorageManager s=new StorageManager(dir)) {
            s.createTablePages("t");int p=s.allocatePageForTable("t").get(0);for(int i=0;i<3;i++)s.insertRecord(p,Map.of("id",i),null,0);
            Map<String,Object> response=StorageCli.handle(s,Map.of("requestId","del","op","delete_rows","table","t","rowIds",List.of(Map.of("pageId",p,"slotId",1))));
            assertEquals(1,data(response).get("deleted"));assertEquals(2,s.scanRecords(p,null).size());assertEquals(0,((Map<?,?>)s.readRecord(p,0,null).get("row")).get("id"));assertEquals(2,((Map<?,?>)s.readRecord(p,2,null).get("row")).get("id"));
            Map<String,Object> bad=StorageCli.handle(s,Map.of("op","delete_rows","table","t","rowIds",List.of(Map.of("pageId",p,"slotId",0),Map.of("pageId",999,"slotId",0))));assertEquals(false,bad.get("ok"));assertEquals(2,s.scanRecords(p,null).size());
        }
    }

    @Test void bplusInvariantsLeafOrderMergeAndRootCollapse() {
        try (StorageManager s=new StorageManager(dir,8,"LRU",true)) {
            s.createIndex(3,true,"INT");for(int i=0;i<80;i++)s.indexInsert(3,i,rid(i));
            assertTrue((Integer)s.validateIndex(3).get("height")>1);List<Map<String,Object>> rows=s.indexRange(3,null,null);for(int i=0;i<80;i++)assertEquals(rid(i),rows.get(i));
            for(int i=0;i<79;i++)s.indexDelete(3,i,null);assertEquals(Map.of("height",1,"nodes",1,"leaves",1),s.validateIndex(3));
        }
        try (StorageManager reopened=new StorageManager(dir)) { assertEquals(List.of(rid(79)),reopened.indexSearch(3,79));assertEquals(1,reopened.validateIndex(3).get("height")); }
    }

    @Test void completeWalCorruptionFailsButUnterminatedTailIsDiscarded() throws Exception {
        Path tail=dir.resolve("tail");try(StorageManager s=new StorageManager(tail)){s.allocatePage();}
        Files.writeString(tail.resolve("wal.log"),"{unfinished",StandardCharsets.UTF_8,StandardOpenOption.APPEND);
        try(StorageManager reopened=new StorageManager(tail)){assertEquals(0,reopened.lastRecovery().get("replayed"));}
        Path corrupt=dir.resolve("corrupt");try(StorageManager s=new StorageManager(corrupt)){s.allocatePage();}
        Files.writeString(corrupt.resolve("wal.log"),"{}\n",StandardCharsets.UTF_8,StandardOpenOption.APPEND);
        assertEquals("WAL_CORRUPT",assertThrows(StorageException.class,()->new StorageManager(corrupt)).code());
        assertDoesNotThrow(()->new StorageManager(corrupt.resolve("other")).close());
    }

    @Test void operatingSystemLeaseRejectsSecondJvm() throws Exception {
        Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),LeaseHarness.class.getName(),dir.toString()).redirectErrorStream(true).start();
        try(BufferedReader out=new BufferedReader(new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8))){assertEquals("READY",out.readLine());assertEquals("STORAGE_BUSY",assertThrows(StorageException.class,()->new StorageManager(dir)).code());}
        finally {child.destroyForcibly();child.waitFor();}
        assertDoesNotThrow(()->new StorageManager(dir).close());
    }

    @SuppressWarnings("unchecked") private static Map<String,Object> data(Map<String,Object> response){assertEquals(true,response.get("ok"));return(Map<String,Object>)response.get("data");}
    private static int lastVictim(List<Map<String,Object>> events){return(Integer)events.stream().filter(e->"EVICT".equals(e.get("event"))).reduce((a,b)->b).orElseThrow().get("victimPageId");}
}
