package org.chainpage.storage;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

final class TablePageMap {
    private final Path path;private final PageManager pages;private final Map<String,List<Integer>> tables=new TreeMap<>();
    TablePageMap(Path root,PageManager pages){path=root.resolve("table_pages.json");this.pages=pages;load();}
    synchronized void load(){tables.clear();if(!Files.exists(path)){persist();return;}try{JsonNode n=JsonFiles.parse(Files.readAllBytes(path),"STORAGE_METADATA_CORRUPT");if(!n.isObject())throw bad("根不是对象");Set<Integer> owned=new HashSet<>();n.fields().forEachRemaining(e->{String name=normalize(e.getKey());if(tables.containsKey(name)||!e.getValue().isArray())throw bad("映射非法");List<Integer> ids=new ArrayList<>();for(JsonNode v:e.getValue()){if(!v.isIntegralNumber()||!v.canConvertToInt()||v.intValue()<0||!pages.isAllocated(v.intValue())||!owned.add(v.intValue()))throw bad("pageId 未分配、非法或被多个表引用");ids.add(v.intValue());}tables.put(name,ids);});}catch(IOException e){throw new StorageException("STORAGE_METADATA_CORRUPT",e.getMessage(),null,e);}}
    synchronized void persist(){JsonFiles.replace(path,JsonFiles.json(tables));}
    static String normalize(String s){if(s==null||s.trim().isEmpty())throw new StorageException("STORAGE_INVALID_TABLE","table 必须是非空字符串");return s.trim().toLowerCase(Locale.ROOT);}
    synchronized List<Integer> create(String table){String n=normalize(table);if(tables.containsKey(n))throw new StorageException("STORAGE_TABLE_EXISTS","表已存在");tables.put(n,new ArrayList<>());try{persist();return List.of();}catch(RuntimeException e){tables.remove(n);throw e;}}
    synchronized List<Integer> append(String table,int id){pages.requireAllocated(id);List<Integer> ids=require(table);if(tables.values().stream().anyMatch(x->x.contains(id)))throw new StorageException("STORAGE_TABLE_EXISTS","页已属于表",id);ids.add(id);try{persist();return List.copyOf(ids);}catch(RuntimeException e){ids.remove((Integer)id);throw e;}}
    synchronized List<Integer> list(String table){return List.copyOf(require(table));}
    synchronized List<Integer> remove(String table){String n=normalize(table);List<Integer> ids=require(n);tables.remove(n);try{persist();return ids;}catch(RuntimeException e){tables.put(n,ids);throw e;}}
    private List<Integer> require(String t){String n=normalize(t);List<Integer> ids=tables.get(n);if(ids==null)throw new StorageException("STORAGE_TABLE_NOT_FOUND","表不存在");return ids;}
    synchronized boolean owns(int id){return tables.values().stream().anyMatch(x->x.contains(id));}
    private StorageException bad(String m){return new StorageException("STORAGE_METADATA_CORRUPT",m);}
    Path path(){return path;}
}
