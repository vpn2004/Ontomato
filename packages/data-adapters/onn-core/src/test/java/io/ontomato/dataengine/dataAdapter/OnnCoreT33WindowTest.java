package io.ontomato.dataengine.dataAdapter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OnnCoreT33WindowTest {
    @Test void windowReadsOnlyRequestedRangeAndCloses() throws Exception {
        try(Fixture f=new Fixture()) {
            var result=new OnnCoreWindowReader(f.client).read(JSONObject.of("steps",new JSONArray()),2,3,Map.of("p","/p"));
            assertEquals(4,result.rows().size());assertEquals(10,result.total());assertEquals(2,result.pages());assertTrue(result.released());assertFalse(result.sourceExhausted());assertEquals(1,f.closed.get());
            assertEquals("adapter_identity_json_utf16_v1",f.last.getJSONObject("pagination").getString("binding_ordering"));
        }
    }
    @Test void staleContinuationDropsEarlierPagesAndRestarts() throws Exception {
        try(Fixture f=new Fixture()) {
            f.stale=true;
            var result=new OnnCoreWindowReader(f.client).read(JSONObject.of("steps",new JSONArray()),2,3,Map.of("p","/p"));
            assertEquals(2,result.attempts());assertEquals(4,result.rows().size());assertEquals(2,f.closed.get());
        }
    }
    @Test void restartedWindowSharesRowBudgetButIndependentReadsDoNot() throws Exception {
        try (Fixture f = new Fixture()) {
            var reader = new OnnCoreWindowReader(f.client, 5, 32L * 1024 * 1024);
            assertEquals(4, reader.read(JSONObject.of("steps", new JSONArray()), 2, 3, Map.of("p", "/p")).rows().size());
            f.stale = true;
            var error = assertThrows(IllegalStateException.class,
                () -> reader.read(JSONObject.of("steps", new JSONArray()), 2, 3, Map.of("p", "/p")));
            assertTrue(error.getMessage().contains("row budget"));
            assertEquals(3, f.closed.get());
        }
    }
    @Test void restartedWindowSharesByteBudget() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals(4, new OnnCoreWindowReader(f.client).read(JSONObject.of("steps", new JSONArray()), 2, 3, Map.of("p", "/p")).rows().size());
            long twoPageBytes = f.responseBytes.get();
            f.stale = true;
            var error = assertThrows(IllegalStateException.class,
                () -> new OnnCoreWindowReader(f.client, 100_000, twoPageBytes)
                    .read(JSONObject.of("steps", new JSONArray()), 2, 3, Map.of("p", "/p")));
            assertTrue(error.getMessage().contains("byte budget"));
            assertEquals(3, f.closed.get());
        }
    }
    @Test void incompatibleOrderingOrDuplicateRowsNeverBecomeAnAnswer() throws Exception {
        for(String mode:List.of("ordering","duplicate","version","total","missing_close"))try(Fixture f=new Fixture()) {
            f.fault=mode;
            assertThrows(Exception.class,()->new OnnCoreWindowReader(f.client).read(JSONObject.of("steps",new JSONArray()),2,3,Map.of("p","/p")));
            if(!mode.equals("missing_close"))assertEquals(1,f.closed.get());
        }
    }
    @Test void capacityAdmissionRetriesButForbiddenDoesNot() throws Exception {
        try (Fixture f = new Fixture()) {
            f.capacity = 2;
            assertTrue(new OnnCoreWindowReader(f.client).read(JSONObject.of("steps", new JSONArray()), 2, 3, Map.of("p", "/p")).released());
        }
        try (Fixture f = new Fixture()) {
            f.forbidden = true;
            assertThrows(OnnCoreHttpClient.OnnHttpException.class, () -> new OnnCoreWindowReader(f.client).read(JSONObject.of("steps", new JSONArray()), 2, 3, Map.of("p", "/p")));
            assertEquals(1, f.requests.get());
        }
    }
    @Test void cancellationAfterFirstPageNeverPublishesPrefix() throws Exception {
        try (Fixture f = new Fixture()) {
            f.blockNext = true;
            var finished = new java.util.concurrent.CountDownLatch(1);
            var returned = new java.util.concurrent.atomic.AtomicBoolean();
            var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
            Thread caller = new Thread(() -> {
                try { new OnnCoreWindowReader(f.client).read(JSONObject.of("steps", new JSONArray()), 2, 3, Map.of("p", "/p")); returned.set(true); }
                catch (Exception expected) { interrupted.set(Thread.currentThread().isInterrupted()); }
                finally { finished.countDown(); }
            });
            try {
                caller.start(); assertTrue(f.nextEntered.await(2, java.util.concurrent.TimeUnit.SECONDS));
                caller.interrupt(); assertTrue(finished.await(2, java.util.concurrent.TimeUnit.SECONDS));
                assertFalse(returned.get()); assertTrue(interrupted.get());
            } finally { f.release.countDown(); caller.interrupt(); caller.join(3000); }
        }
    }
    @Test void forbiddenContinuationDiscardsAlreadyReadPrefix() throws Exception {
        try (Fixture f = new Fixture()) {
            f.fault = "forbidden_next";
            assertThrows(OnnCoreHttpClient.OnnHttpException.class, () -> new OnnCoreWindowReader(f.client).read(JSONObject.of("steps", new JSONArray()), 2, 3, Map.of("p", "/p")));
            assertEquals(2, f.requests.get()); assertEquals(1, f.closed.get());
        }
    }
    @Test void omittedOrZeroLimitNeverSelectsWindow() {
        JSONObject query = OnnCoreGraphCompilerTest.query();
        OnnCoreSchema schema = new OnnCoreSchema(OnnCoreGraphCompilerTest.schemaWire());
        JSONObject output = query.getJSONObject("answer").getJSONArray("steps").getJSONObject(0).getJSONObject("output");
        output.remove("limit");
        assertNull(new OnnCoreGraphCompiler(query, schema, schema.ontology(), true).window());
        output.put("limit", JSONObject.of("count", 0));
        assertNull(new OnnCoreGraphCompiler(query, schema, schema.ontology(), true).window());
    }
    @Test void compilerWindowEligibilityRequiresNoGlobalResidualAndSameVariableOrder() {
        JSONObject query=OnnCoreGraphCompilerTest.query();
        OnnCoreSchema schema=new OnnCoreSchema(OnnCoreGraphCompilerTest.schemaWire());
        var plan=new OnnCoreGraphCompiler(query,schema,schema.ontology(),true);
        assertNotNull(plan.window());
        query.getJSONObject("answer").getJSONArray("steps").getJSONObject(0).getJSONObject("output").put("sort",JSONObject.of("fields",JSONArray.of(JSONObject.of("variable","a","field","id","order","asc"))));
        assertNull(new OnnCoreGraphCompiler(query,schema,schema.ontology(),true).window());
    }
    static class Fixture implements AutoCloseable {
        final HttpServer server;
        final OnnCoreHttpClient client;
        final AtomicInteger closed=new AtomicInteger(), requests=new AtomicInteger();
        final java.util.concurrent.atomic.AtomicLong responseBytes = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newCachedThreadPool();
        final java.util.concurrent.CountDownLatch nextEntered = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
        int capacity; boolean forbidden, blockNext;
        JSONObject last;String fault="";boolean stale;
        Fixture()throws Exception{
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);
            server.createContext("/api/v1/query/windows/close",e->{closed.incrementAndGet();e.sendResponseHeaders(204,-1);e.close();});
            server.createContext("/api/v1/query/pipeline",e->{
                requests.incrementAndGet();
                if (forbidden || capacity-- > 0) {
                    byte[] raw = JSON.toJSONBytes(JSONObject.of("code", forbidden ? "FORBIDDEN" : "SNAPSHOT_CAPACITY_EXCEEDED"));
                    e.sendResponseHeaders(forbidden ? 403 : 429, raw.length); e.getResponseBody().write(raw); e.close(); return;
                }
                last=JSON.parseObject(new String(e.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
                boolean next=last.getJSONObject("pagination").containsKey("after");
                if (next && blockNext) { nextEntered.countDown(); try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } }
                if (next && fault.equals("forbidden_next")) { byte[] raw = JSON.toJSONBytes(JSONObject.of("code", "FORBIDDEN")); e.sendResponseHeaders(403, raw.length);e.getResponseBody().write(raw);e.close();return; }
                if(next&&stale){stale=false;byte[] raw=JSON.toJSONBytes(JSONObject.of("code","SNAPSHOT_STALE"));e.sendResponseHeaders(409,raw.length);e.getResponseBody().write(raw);e.close();return;}
                JSONArray rows=new JSONArray();int start=next?2:0;
                for(int i=start;i<start+2;i++)rows.add(JSONObject.of("p",JSONObject.of("id",fault.equals("duplicate")?"n0":"n"+i,"class_path","/p","attributes",new JSONObject())));
                JSONObject meta=JSONObject.of("result_kind","bindings","consistency","version_checked","ordering_contract",fault.equals("ordering")?"unknown":"adapter_identity_json_utf16_v1","data_version",next&&fault.equals("version")?"v2":"v1","total",next&&fault.equals("total")?11:10,"total_exact",true,"page_count",2,"has_more",true,"next_cursor",next?"cursor2":"cursor1","close_token",fault.equals("missing_close")?null:"close1");
                byte[] raw=JSON.toJSONBytes(JSONObject.of("bindings",rows,"pagination",meta));responseBytes.addAndGet(raw.length);e.sendResponseHeaders(200,raw.length);e.getResponseBody().write(raw);e.close();
            });server.start();client=new OnnCoreHttpClient("http://127.0.0.1:"+server.getAddress().getPort());
        }
        public void close(){release.countDown();server.stop(0);executor.shutdownNow();}
    }
}
