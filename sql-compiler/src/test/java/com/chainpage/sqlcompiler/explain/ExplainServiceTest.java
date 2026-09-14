package com.chainpage.sqlcompiler.explain;

import com.chainpage.sqlcompiler.CompilerFrontEnd;
import com.chainpage.sqlcompiler.ast.JsonCodec;
import com.chainpage.sqlcompiler.extension.*;
import com.chainpage.sqlcompiler.optimizer.*;
import java.util.*;
import static com.chainpage.sqlcompiler.explain.ExplainData.*;

public final class ExplainServiceTest {
    private static int checks;
    public static void main(String[] args) {
        InMemoryExtensionExecutor db = new InMemoryExtensionExecutor();
        execute(db,"CREATE TABLE t(id INT,v INT); INSERT INTO t(id,v) VALUES(1,10); INSERT INTO t(id,v) VALUES(2,NULL); INSERT INTO t(id,v) VALUES(3,10);");
        Map<String,Object> snapshot=db.snapshot(); ExplainService service=new ExplainService(snapshot);
        for(String sql:List.of("SELECT id FROM t WHERE 1=1;", "SELECT id FROM t WHERE (1=1 AND v>0) OR v IS NULL ORDER BY id;",
                "SELECT v,COUNT(*) AS n FROM t GROUP BY v HAVING COUNT(*)>0 ORDER BY v;",
                "SELECT SUM(v) AS total FROM t;", "SELECT a.id,b.v FROM t a JOIN t b ON a.id=b.id ORDER BY a.id;",
                "SELECT id FROM t WHERE NULL;", "SELECT id FROM t WHERE (1=2) AND v IS NULL;", "SELECT id FROM t WHERE 0.1+0.2=0.3;")) {
            ExplainResponse response=service.explain(new ExplainRequest("EXPLAIN "+sql)); ok(response);
            check(response.data().keySet().equals(Set.of("tokens","ast","semantic","plan","optimizedPlan","tree")),"exact six fields");
            var compiled=new CompilerFrontEnd().compile(Map.of("requestId","ordinary","sql",sql,"catalogSnapshot",snapshot,"optimize",true));
            check(compiled.ok(),"ordinary compile succeeds"); var statement=nodes(compiled.data().get("statements")).get(0);
            check(withoutLocations(statement.get("plan")).equals(withoutLocations(response.data().get("plan"))),"same original plan across entry points");
            check(withoutLocations(statement.get("optimizedPlan")).equals(withoutLocations(response.data().get("optimizedPlan"))),"same optimized plan across entry points");
            var original=db.execute(Map.of("plan",response.data().get("plan"))); var optimized=db.execute(Map.of("plan",response.data().get("optimizedPlan")));
            check(original.ok() && optimized.ok(),"standard plans execute"); check(original.data().equals(optimized.data()),"optimization preserves rows, nulls, order");
            var repeated=new Optimizer().optimize(new OptimizeRequest(map(response.data().get("optimizedPlan"))));
            check(repeated.ok() && repeated.optimizedPlan().equals(response.data().get("optimizedPlan")) && repeated.appliedRules().isEmpty(),"idempotent");
            check(response.data().get("tree").equals(new PlanTreeFormatter().format(map(response.data().get("optimizedPlan")))),"tree from optimized plan");
        }
        var basic=service.explain(new ExplainRequest("EXPLAIN SELECT id FROM t WHERE 1=1;")); ok(basic);
        check(basic.data().get("tree").equals("Project [id] -> [id:INT]\n└── SeqScan [table=t] -> [id:INT, v:INT]\n"),"deterministic base tree");
        String before=JsonCodec.stringify(execute(db,"SELECT * FROM t;")), metadata=JsonCodec.stringify(db.snapshot());
        for(String sql:List.of("UPDATE t SET v=v WHERE id/0>1;","DELETE FROM t;","INSERT INTO t(id) VALUES(99);","CREATE TABLE fresh(id INT);")) {
            var r=service.explain(new ExplainRequest("EXPLAIN "+sql)); ok(r); check(!r.data().containsKey("rows"),"no execution results");
        }
        check(before.equals(JsonCodec.stringify(execute(db,"SELECT * FROM t;"))),"EXPLAIN reads no data and writes nothing");
        check(metadata.equals(JsonCodec.stringify(db.snapshot())),"EXPLAIN does not persist catalog");
        positionsAndErrors(service); isolation(service,snapshot); handwrittenPlans();
        System.out.println("EXPLAIN contract tests passed: "+checks);
    }
    private static void positionsAndErrors(ExplainService service) {
        var r=service.explain(new ExplainRequest("-- prefix\n  eXpLaIn /* ; */\nSELECT id FROM t;")); ok(r);
        var tokens=nodes(r.data().get("tokens")); check(tokens.get(0).get("line").equals(2) && tokens.get(0).get("column").equals(3),"control position");
        check(map(map(r.data().get("ast")).get("loc")).equals(Map.of("line",3,"column",1)),"AST source position");
        check(tokens.stream().filter(t->t.get("type").equals("EOF")).count()==1,"one EOF");
        for(String sql:List.of("", "-- EXPLAIN", "SELECT id FROM t;", "EXPLAIN", "EXPLAIN SELECT id FROM t; SELECT id FROM t;", "EXPLAIN EXPLAIN SELECT id FROM t;", "EXPLAIN SELECT FROM t;", "EXPLAIN SELECT id FROM t")) error(service.explain(new ExplainRequest(sql)),"PARSER");
        error(service.explain(new ExplainRequest("EXPLAIN SELECT @ FROM t;")),"LEXER");
        error(service.explain(new ExplainRequest("EXPLAIN SELECT * FROM absent;")),"SEMANTIC");
        error(service.explain(new ExplainRequest("EXPLAIN SELECT AVG(v) FROM t;")),"PLANNER");
        error(service.explain(null),"PARSER"); error(new ExplainService(null).explain(new ExplainRequest("EXPLAIN SELECT id FROM t;")),"SEMANTIC");
        var parser=service.explain(new ExplainRequest("EXPLAIN SELECT id t;")); error(parser,"PARSER"); check(parser.error().get("column").equals(19),"unshifted parser error");
    }
    private static void isolation(ExplainService service,Map<String,Object> snapshot) {
        var first=service.explain(new ExplainRequest("EXPLAIN SELECT id FROM t WHERE 1=1;")); var second=service.explain(new ExplainRequest("EXPLAIN SELECT id FROM t WHERE 1=1;")); ok(first); ok(second);
        String before=JsonCodec.stringify(second.toMap()); map(first.data().get("ast")).put("kind","changed");
        check(map(first.data().get("semantic")).get("kind").equals("SelectStmt"),"AST independent from semantic");
        map(first.data().get("optimizedPlan")).put("kind","changed"); check(map(first.data().get("plan")).get("kind").equals("Project"),"plans independent");
        check(before.equals(JsonCodec.stringify(second.toMap())),"responses isolated");
        nodes(snapshot.get("tables")).get(0).put("name","changed"); ok(service.explain(new ExplainRequest("EXPLAIN SELECT id FROM t;")));
    }
    private static void handwrittenPlans() {
        Map<String,Object> scan=Map.of("kind","SeqScan","table","t","schema",List.of(Map.of("name","id","dataType","INT")),"children",List.of());
        Map<String,Object> group=Map.of("kind","GroupBy","keys",List.of("id"),"aggregates",List.of(Map.of("function","COUNT","column","*","alias","n")),"children",List.of(scan),"schema",List.of(Map.of("name","id","dataType","INT"),Map.of("name","n","dataType","BIGINT")));
        Map<String,Object> sort=Map.of("kind","Sort","keys",List.of(Map.of("column","n","direction","DESC")),"children",List.of(group),"schema",group.get("schema"));
        check(new PlanTreeFormatter().format(sort).contains("GroupBy [keys=[id]; aggregates=COUNT(*) AS n]"),"document GroupBy formatted");
        Map<String,Object> join=Map.of("kind","Join","leftKey","id","rightKey","id","children",List.of(scan,scan),"schema",scan.get("schema"));
        check(new PlanTreeFormatter().format(join).contains("Join [id = id]"),"document Join formatted");
        check(new ExplainOptimizer().optimize(sort).ok(),"document Sort/GroupBy optimized");
        var unknown=new LinkedHashMap<>(scan); unknown.put("kind","Unknown");
        check(!new ExplainOptimizer().optimize(unknown).ok(),"unknown optimizer plan rejected");
        boolean rejected=false; try {new PlanTreeFormatter().format(unknown);} catch(IllegalArgumentException e) {rejected=true;} check(rejected,"unknown formatter plan rejected");
    }
    private static Object withoutLocations(Object value) {
        if(value instanceof Map<?,?> m) {Map<String,Object> out=new LinkedHashMap<>();m.forEach((k,v)->{if(!k.equals("loc"))out.put((String)k,withoutLocations(v));});return out;}
        if(value instanceof List<?> l) {List<Object> out=new ArrayList<>();for(Object v:l)out.add(withoutLocations(v));return out;}return value;
    }
    private static Object execute(InMemoryExtensionExecutor db,String sql) {var r=new SqlExtension().executeSql(sql,db.snapshot(),db);check(r.ok(),"execute: "+r.error());return r.toMap();}
    private static void ok(ExplainResponse r) {check(r.ok(),"explain: "+r.error());}
    private static void error(ExplainResponse r,String stage) {check(!r.ok() && r.data()==null && stage.equals(r.error().get("stage")),"expected "+stage+": "+r.toMap());}
    private static void check(boolean condition,String message) {checks++;if(!condition)throw new AssertionError(message);}
}
