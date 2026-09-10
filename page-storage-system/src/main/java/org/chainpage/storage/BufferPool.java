package org.chainpage.storage;

import java.util.*;

public final class BufferPool {
    private static final class Frame {byte[] data;long generation;boolean dirty;final List<Long> lsns=new ArrayList<>();Frame(byte[]d,long g,boolean x){data=d;generation=g;dirty=x;}}
    private final PageManager pages;private final WalManager wal;private final LockManager locks;private final int capacity;private ReplacementPolicy policy;
    private final Map<Integer,Frame> frames=new HashMap<>();private final List<Map<String,Object>> events=new ArrayList<>();private long hits,misses,evictions,flushes;
    BufferPool(PageManager p,WalManager w,LockManager l,int capacity,String policy){if(capacity<=0)throw new StorageException("BUFFER_INVALID_CAPACITY","capacity 必须为正整数");pages=p;wal=w;locks=l;this.capacity=capacity;this.policy=ReplacementPolicy.make(policy);pages.setPageInvalidator(this::beforeFree);}
    private String owner(String owner){return owner==null||owner.isBlank()?"buffer-"+Thread.currentThread().getId():owner;}
    public synchronized Page getPage(int id,String owner){pages.requireAllocated(id);try(LockManager.Lease ignored=locks.acquire(id,"READ",owner(owner))){Frame f=frames.get(id);if(f!=null&&f.generation!=pages.generation(id)){frames.remove(id);policy.remove(id);f=null;}if(f!=null){hits++;policy.access(id);event("HIT",id,null);return new Page(f.data.clone(),true,f.dirty);}misses++;event("MISS",id,null);byte[] data=pages.readPage(id);evict();frames.put(id,new Frame(data,pages.generation(id),false));policy.insert(id);return new Page(data.clone(),false,false);}}
    public synchronized Put putPage(int id,byte[] data,boolean dirty,String owner,int tx){FileManager.page(data,id);pages.requireAllocated(id);try(LockManager.Lease ignored=locks.acquire(id,"WRITE",owner(owner))){Frame f=frames.get(id);if(f!=null&&f.generation!=pages.generation(id)){frames.remove(id);policy.remove(id);f=null;}byte[] before=f==null?pages.readPage(id):f.data.clone();Integer victim=null,flushed=null;if(f==null){int[] e=evict();victim=e[0]<0?null:e[0];flushed=e[1]<0?null:e[1];f=new Frame(data.clone(),pages.generation(id),dirty);frames.put(id,f);policy.insert(id);}else{f.data=data.clone();f.dirty|=dirty;policy.access(id);}if(f.dirty&&!Arrays.equals(before,data))f.lsns.add(wal.append(tx,id,before,data));return new Put(victim,flushed);}}
    private int[] evict(){if(frames.size()<capacity)return new int[]{-1,-1};int id=policy.victim(frames.keySet());Frame f=frames.get(id);try(LockManager.Lease ignored=locks.acquire(id,"WRITE",owner(null))){boolean wrote=flushFrame(id,f);frames.remove(id);policy.remove(id);evictions++;event("EVICT",id,id);return new int[]{id,wrote?id:-1};}}
    public synchronized boolean flushPage(int id,String owner){try(LockManager.Lease ignored=locks.acquire(id,"WRITE",owner(owner))){Frame f=frames.get(id);if(f==null)throw new StorageException("BUFFER_PAGE_NOT_RESIDENT","页不在 Buffer Pool",id);flushFrame(id,f);return true;}}
    public synchronized List<Integer> flushAll(){List<Integer> out=new ArrayList<>();for(int id:new ArrayList<>(frames.keySet())){try(LockManager.Lease ignored=locks.acquire(id,"WRITE",owner(null))){Frame f=frames.get(id);if(f!=null&&flushFrame(id,f))out.add(id);}}pages.sync();return out;}
    private boolean flushFrame(int id,Frame f){if(!f.dirty)return false;if(!pages.isAllocated(id)||f.generation!=pages.generation(id)){f.dirty=false;f.lsns.clear();return false;}pages.writePage(id,f.data);pages.sync();for(long l:f.lsns)wal.applied(l);f.lsns.clear();f.dirty=false;flushes++;event("FLUSH",id,null);return true;}
    private void beforeFree(int id){try(LockManager.Lease ignored=locks.acquire(id,"WRITE",owner(null))){Frame f=frames.get(id);if(f!=null){flushFrame(id,f);frames.remove(id);policy.remove(id);}}}
    synchronized void discard(int id){Frame f=frames.get(id);if(f!=null&&f.dirty)throw new StorageException("BUFFER_DIRTY_DISCARD","不能丢弃脏页",id);frames.remove(id);policy.remove(id);}
    synchronized void forceDiscard(int id){frames.remove(id);policy.remove(id);}
    synchronized void clear(){frames.clear();policy=ReplacementPolicy.make(policy.name());}
    synchronized Map<String,Object> stats(){Map<String,Object> m=JsonFiles.map();m.put("capacity",capacity);m.put("size",frames.size());m.put("hits",hits);m.put("misses",misses);m.put("evictions",evictions);m.put("flushes",flushes);m.put("policy",policy.name());return m;}
    synchronized Map<String,Object> setPolicy(String name){ReplacementPolicy p=ReplacementPolicy.make(name);frames.keySet().forEach(p::insert);policy=p;return Map.of("policy",p.name());}
    synchronized List<Map<String,Object>> events(boolean clear){List<Map<String,Object>> out=events.stream().map(LinkedHashMap::new).map(x->(Map<String,Object>)x).toList();if(clear)events.clear();return out;}
    private void event(String kind,int id,Integer victim){Map<String,Object> e=JsonFiles.map();e.put("event",kind);e.put("pageId",id);e.put("victimPageId",victim);events.add(e);}
    public record Page(byte[] data,boolean hit,boolean dirty){} public record Put(Integer evicted,Integer flushedPageId){}
}
