package io.ontomato.dataengine.dataAdapter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.ontomato.dataengine.dataAdapter.DslExecutionMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Optional read-only smoke test. No endpoint or credentials are stored in the repository. */
@EnabledIfEnvironmentVariable(named = "ONTOMATO_ONN_LIVE_TEST", matches = "true")
class OnnCoreLiveTest {
    @Test void importsSchemaAndChecksBaseReadsOfAnExplicitSmallClass() throws Exception {
        String url = System.getenv("ONTOMATO_ONN_LIVE_URL");
        String className = System.getenv("ONTOMATO_ONN_LIVE_CLASS");
        assertNotNull(url, "Explicit live tests require ONTOMATO_ONN_LIVE_URL");
        assertNotNull(className, "Explicit live tests require ONTOMATO_ONN_LIVE_CLASS");
        String token = System.getenv("ONTOMATO_ONN_LIVE_TOKEN");
        OnnCoreHttpClient client = new OnnCoreHttpClient(url, token);
        OnnCoreSchema schema = new OnnCoreSchema(client.schema());
        schema.requireClass(className);
        Map<String, Object> mapping = schema.ontology();
        boolean snapshot = OnnCoreCapabilities.discover(client).snapshotReads();
        JSONObject sample = client.sample(className);
        assertEquals(className, sample.getString("class"));
        long total = sample.getLongValue("total_count");
        assertTrue(total > 0 && total <= (snapshot ? 100_000 : OnnCoreDslCompiler.MAX_ROWS),
            "Live class must be nonempty and within the negotiated complete-read budget");
        JSONArray samples = sample.getJSONArray("samples");
        assertNotNull(samples); assertFalse(samples.isEmpty());
        String id = samples.getJSONObject(0).getString("id");
        assertNotNull(id);

        DataAdapterConnection connection = new DataAdapterConnection(); connection.setUrl(url); connection.setToken(token);
        OnnCoreAdapter adapter = new OnnCoreAdapter(connection, mapping);
        JSONObject exact = execute(adapter, query(className, JSONObject.of("field", "id", "operator", "=", "value", id)));
        assertEquals(id, exact.getJSONArray("answer").getJSONObject(0).getString("id"));
        assertEquals("BASE", exact.getJSONObject("evidence").getString("view"));

        JSONObject countQuery = query(className, null);
        JSONObject field = countQuery.getJSONObject("answer").getJSONArray("steps").getJSONObject(0)
            .getJSONObject("output").getJSONArray("fields").getJSONObject(0);
        field.put("function", "count"); field.put("as", "count");
        JSONObject count = execute(adapter, countQuery);
        assertEquals(total, count.getJSONArray("answer").getJSONObject(0).getLongValue("count"));
        assertEquals(total, count.getJSONObject("evidence").getLongValue("objectsRead"));
        assertEquals(snapshot, count.getJSONObject("evidence").getBooleanValue("snapshotConsistent"));
        if (snapshot) {
            assertTrue(count.getJSONObject("evidence").getBooleanValue("complete"));
            assertTrue(count.getJSONObject("evidence").getBooleanValue("snapshotReleased"));
            assertFalse(count.getJSONObject("evidence").getBooleanValue("queryPushdown"));
        }
        assertFalse(adapter.capabilities().graphRead());
    }

    private static JSONObject execute(OnnCoreAdapter adapter, JSONObject query) throws Exception {
        return JSON.parseArray(adapter.executeDsl(JSON.toJSONString(List.of(query)), null, DslExecutionMode.OPTIMIZED, new ArrayList<>())).getJSONObject(0);
    }
    private static JSONObject query(String className, JSONObject predicate) {
        JSONObject object = JSONObject.of("class", className, "variable", "o");
        if (predicate != null) object.put("conditions", JSONObject.of("properties", predicate));
        JSONObject pattern = JSONObject.of("objects", List.of(object), "relationships", List.of());
        JSONObject output = JSONObject.of("to_user", true, "fields", List.of(JSONObject.of("variable", "o", "field", "id", "as", "id")),
            "limit", JSONObject.of("count", 1));
        return JSONObject.of("answer", JSONObject.of("steps", List.of(JSONObject.of("graph", JSONObject.of("patterns", List.of(pattern)), "output", output))));
    }
}
