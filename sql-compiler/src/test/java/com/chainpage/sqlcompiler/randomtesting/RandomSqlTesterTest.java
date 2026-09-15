package com.chainpage.sqlcompiler.randomtesting;

import com.chainpage.sqlcompiler.ast.JsonCodec;
import com.chainpage.sqlcompiler.extension.ExtensionResponse;
import java.util.*;

public final class RandomSqlTesterTest {
    private static int checks;
    public static void main(String[] args) {
        Set<String> categories = new HashSet<>();
        for (long seed : new long[]{0, 1, 42, -17, Long.MIN_VALUE, Long.MAX_VALUE}) {
            for (String mode : List.of("valid", "invalid", "mixed")) {
                Map<String,Object> request = request(seed,120,mode);
                RandomSqlTester tester = new RandomSqlTester();
                var response = tester.run(request); check(response.ok(),"valid request");
                var data=response.data();
                check(data.keySet().equals(Set.of("total","accepted","rejected","crashes","wrongAccept","wrongReject","locationErrors","cases")),"exact response fields");
                check(data.get("total").equals(120),"requested count");
                check(data.get("accepted").equals(mode.equals("valid") ? 120 : mode.equals("mixed") ? 60 : 0),"accepted count");
                check(data.get("rejected").equals(mode.equals("invalid") ? 120 : mode.equals("mixed") ? 60 : 0),"rejected count");
                for(String metric:List.of("crashes","wrongAccept","wrongReject","locationErrors"))check(data.get(metric).equals(0),metric+": "+data.get(metric));
                check(JsonCodec.stringify(response.toMap()).equals(JsonCodec.stringify(tester.run(request).toMap())),"seed reproducibility including diagnostics");
                for(var item:cases(response)) {
                    categories.add((String)item.get("category"));
                    check(item.get("actual").equals(item.get("expected")),"expectation independent of compiler");
                    check(item.get("catalogSnapshot") instanceof Map<?,?>,"replayable snapshot");
                    if(item.get("expectedStage")!=null) check(item.get("expectedStage").equals(((Map<?,?>)item.get("error")).get("stage")),"expected error stage");
                }
            }
        }
        check(categories.size()==9,"all valid and invalid categories sampled");
        invalidRequests(); injectedFailures(); isolation();
        System.out.println("Random SQL tester checks passed: "+checks);
    }
    private static void invalidRequests() {
        var tester=new RandomSqlTester();check(!tester.run(null).ok(),"null request");check(!tester.run(Map.of()).ok(),"missing fields");
        for(Object value:List.of(0,-1,1.5,"2",true,2147483648L,Double.NaN))check(!tester.run(Map.of("seed",1,"count",value,"mode","mixed")).ok(),"invalid count");
        for(Object value:List.of("1",true,1.25,Double.POSITIVE_INFINITY))check(!tester.run(Map.of("seed",value,"count",1,"mode","valid")).ok(),"invalid seed");
        check(!tester.run(request(1,1,"unknown")).ok(),"invalid mode");
        check(tester.run(request(1,1,"mixed")).ok(),"one mixed case");
    }
    private static void injectedFailures() {
        ExtensionResponse accepted=new ExtensionResponse(true,Map.of("statements",List.of(Map.of("plan",Map.of(),"optimizedPlan",Map.of()))),null);
        var result=new RandomSqlTester(request->accepted).run(request(1,8,"invalid"));
        metric(result,"wrongAccept",8);metric(result,"crashes",0);
        Map<String,Object> error=new LinkedHashMap<>();error.put("stage","PARSER");error.put("code","INJECTED");error.put("message","test");error.put("line",1);error.put("column",1);
        result=new RandomSqlTester(request->new ExtensionResponse(false,null,error)).run(request(1,8,"valid"));
        metric(result,"wrongReject",8);metric(result,"rejected",8);
        error.put("line",999);
        result=new RandomSqlTester(request->new ExtensionResponse(false,null,error)).run(request(1,8,"invalid"));metric(result,"locationErrors",8);
        for(int kind=0;kind<3;kind++) {
            final int failure=kind;
            result=new RandomSqlTester(request->{if(failure==0)throw new IllegalStateException("injected");if(failure==1)throw new AssertionError("injected");throw new StackOverflowError("injected");}).run(request(1,8,"mixed"));
            metric(result,"crashes",8);metric(result,"accepted",0);metric(result,"rejected",0);metric(result,"wrongReject",0);
            check(cases(result).size()==8,"continues after crashes");
        }
        result=new RandomSqlTester(request->new ExtensionResponse(true,Map.of(),null)).run(request(1,3,"valid"));metric(result,"crashes",3);
    }
    private static void isolation() {
        var tester=new RandomSqlTester();var first=tester.run(request(42,10,"mixed"));String before=JsonCodec.stringify(first.toMap());
        var second=tester.run(request(42,10,"mixed"));cases(second).get(0).put("sql","changed");
        check(before.equals(JsonCodec.stringify(first.toMap())),"reports do not share state");
        check(!tester.run(request(43,10,"mixed")).data().equals(first.data()),"different seeds generate different SQL");
        for(var r:List.of(first,second)) {
            int total=(Integer)r.data().get("total"), accounted=(Integer)r.data().get("accepted")+(Integer)r.data().get("rejected")+(Integer)r.data().get("crashes");
            check(total==accounted,"counter partition");
        }
    }
    private static Map<String,Object> request(long seed,int count,String mode){return Map.of("seed",seed,"count",count,"mode",mode);}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> cases(RandomSqlTestResponse r){return (List<Map<String,Object>>)r.data().get("cases");}
    private static void metric(RandomSqlTestResponse r,String name,int expected){check(r.ok() && r.data().get(name).equals(expected),name);}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
