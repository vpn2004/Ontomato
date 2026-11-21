package io.ontomato.dataengine.dataAdapter;

import com.alibaba.fastjson2.JSONObject;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Runs only against a dedicated scoped-policy OMS fixture with the test-writer and test-reader roles. */
@EnabledIfEnvironmentVariable(named = "ONTOMATO_ONN_CONDITIONAL_LIVE_TEST", matches = "true")
class OnnCoreConditionalWriteLiveTest {
    @Test void versionConflictsReplayConcurrentSingleWinnerAndAuthorizationUseRealHttp() throws Exception {
        var principal = new ConditionalObjectWrites.Principal("test-user", List.of("test-writer"));
        var client = new OnnCoreHttpClient(System.getenv("ONTOMATO_ONN_LIVE_URL"), System.getenv("ONTOMATO_ONN_LIVE_TOKEN"));
        var schema = new OnnCoreSchema(client.asPrincipal(principal).schema());
        var writer = new OnnCoreConditionalWriter(client, schema.ontology(), principal);
        String cls = "/test/a", id = "decision:1";
        var before = writer.read(cls, id);
        var updated = writer.patch(cls, id, JSONObject.of("name", "after"), before.getString("etag"), "decision-1");
        assertEquals(before.getLongValue("version") + 1, updated.getLongValue("version"));
        assertEquals(updated, writer.patch(cls, id, JSONObject.of("name", "after"), before.getString("etag"), "decision-1"));
        var stale = assertThrows(OnnCoreHttpClient.OnnHttpException.class,
            () -> writer.patch(cls, id, JSONObject.of("name", "stale"), before.getString("etag"), "stale-decision"));
        assertEquals(412, stale.status); assertEquals("PRECONDITION_FAILED", stale.code);
        var current = writer.read(cls, id); assertEquals(updated.get("version"), current.get("version"));
        assertEquals("after", current.getJSONObject("object").getString("name"));
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var results = pool.invokeAll(List.of((Callable<Integer>) () -> race(writer, cls, id, current.getString("etag"), "race-1", barrier),
                () -> race(writer, cls, id, current.getString("etag"), "race-2", barrier)));
            assertEquals(List.of(200, 412), results.stream().map(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } }).sorted().toList());
        } finally { pool.shutdownNow(); }
        var afterRace = writer.read(cls, id); assertEquals(current.getLongValue("version") + 1, afterRace.getLongValue("version"));
        var reader = new OnnCoreConditionalWriter(client, schema.ontology(), new ConditionalObjectWrites.Principal("reader-user", List.of("test-reader")));
        var denied = assertThrows(OnnCoreHttpClient.OnnHttpException.class,
            () -> reader.patch(cls, id, JSONObject.of("name", "unauthorized"), afterRace.getString("etag"), "denied-decision"));
        assertEquals(403, denied.status);
        assertEquals(afterRace, writer.read(cls, id));
        JSONObject nullValue = new JSONObject(); nullValue.put("name", null);
        writer.patch(cls, id, nullValue, afterRace.getString("etag"), "set-null-decision");
        assertNull(writer.read(cls, id).getJSONObject("object").get("name"));
    }
    private static int race(OnnCoreConditionalWriter writer, String cls, String id, String etag, String key, CyclicBarrier barrier) throws Exception {
        barrier.await();
        try { writer.patch(cls, id, JSONObject.of("name", key), etag, key); return 200; }
        catch (OnnCoreHttpClient.OnnHttpException error) { return error.status; }
    }
}
