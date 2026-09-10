package org.chainpage.storage;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class PageManager {
    private final Path meta; private final FileManager file;
    private final SortedSet<Integer> allocated=new TreeSet<>(), free=new TreeSet<>();
    private final Map<Integer,Long> generations=new HashMap<>();
    private Runnable invalidator=()->{}; private java.util.function.IntConsumer pageInvalidator=id->{};
    PageManager(Path root){file=new FileManager(root.resolve("pages.dat"));meta=root.resolve("page_allocation.json");load();}
    synchronized void setPageInvalidator(java.util.function.IntConsumer c){pageInvalidator=c;}
    synchronized void load(){allocated.clear();free.clear();generations.clear();
        if(!Files.exists(meta)){persist();return;}
        try{
            JsonNode n=JsonFiles.parse(Files.readAllBytes(meta),"PAGE_METADATA_CORRUPT");
            if(!n.isObject()||!n.has("allocated")||!n.has("free"))throw corrupt("字段缺失");
            readIds(n.get("allocated"),allocated);readIds(n.get("free"),free);
            if(!Collections.disjoint(allocated,free))throw corrupt("allocated/free 重叠");
            JsonNode gs=n.get("generations");if(gs!=null){if(!gs.isObject())throw corrupt("generations 非对象");gs.fields().forEachRemaining(e->{try{int id=Integer.parseInt(e.getKey());JsonNode value=e.getValue();if(!value.isIntegralNumber()||!value.canConvertToLong())throw new Exception();long g=value.longValue();if(id<0||g<0)throw new Exception();generations.put(id,g);}catch(Exception x){throw corrupt("generation 非法");}});}
            long size=Files.size(file.path()); long full=size/FileManager.PAGE_SIZE;if(size%FileManager.PAGE_SIZE!=0)file.truncate(full*FileManager.PAGE_SIZE);
            int pages=Math.toIntExact(full);if(allocated.stream().anyMatch(i->i>=pages)||free.stream().anyMatch(i->i>=pages))throw corrupt("页号超出数据文件");
            boolean changed=false;for(int i=0;i<pages;i++)if(!allocated.contains(i)&&!free.contains(i)){free.add(i);changed=true;}if(changed)persist();
        }catch(IOException|ArithmeticException e){throw new StorageException("PAGE_METADATA_CORRUPT",e.getMessage(),null,e);}
    }
    private void readIds(JsonNode n,Set<Integer> out){if(!n.isArray())throw corrupt("分配表非数组");for(JsonNode v:n){if(!v.isIntegralNumber()||!v.canConvertToInt()||v.intValue()<0||!out.add(v.intValue()))throw corrupt("非法或重复页号");}}
    private StorageException corrupt(String m){return new StorageException("PAGE_METADATA_CORRUPT",m);}
    synchronized void persist(){Map<String,Object> m=JsonFiles.map();m.put("allocated",allocated);m.put("free",free);Map<String,Long> gs=new TreeMap<>();generations.forEach((k,v)->gs.put(k.toString(),v));m.put("generations",gs);JsonFiles.replace(meta,JsonFiles.json(m));}
    public synchronized int allocatePage(){boolean reuse=!free.isEmpty();int id=reuse?free.first():(int)file.pageCount();long old=generation(id);
        if(reuse)file.writeAt(id,new byte[FileManager.PAGE_SIZE]);else{int made=file.appendZero();if(made!=id)throw new StorageException("FILE_IO_ERROR","并发扩页冲突");}
        file.sync();if(reuse)free.remove(id);allocated.add(id);generations.put(id,old+1);
        try{persist();CrashHooks.hit("page_allocate");return id;}catch(RuntimeException e){allocated.remove(id);generations.put(id,old);if(reuse)free.add(id);else file.truncate((long)id*FileManager.PAGE_SIZE);throw e;}}
    public synchronized void freePage(int id){requireAllocated(id);pageInvalidator.accept(id);allocated.remove(id);free.add(id);try{persist();CrashHooks.hit("page_free");}catch(RuntimeException e){free.remove(id);allocated.add(id);throw e;}}
    public synchronized byte[] readPage(int id){requireAllocated(id);return file.readAt(id);}
    public synchronized void writePage(int id,byte[] data){requireAllocated(id);FileManager.page(data,id);file.writeAt(id,data);}
    public synchronized void sync(){file.sync();}
    public synchronized void requireAllocated(int id){FileManager.valid(id);if(!allocated.contains(id))throw new StorageException("PAGE_NOT_ALLOCATED","页不存在或已经释放",id);}
    public synchronized boolean isAllocated(int id){return id>=0&&allocated.contains(id);}
    public synchronized long generation(int id){return generations.getOrDefault(id,0L);}
    public synchronized int allocatedCount(){return allocated.size();}
    synchronized Set<Integer> allocatedIds(){return new TreeSet<>(allocated);}
    Path dataPath(){return file.path();}
}
