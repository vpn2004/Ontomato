package io.ontomato.dataengine.dataAdapter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.ontomato.dataengine.config.BusinessConfig;
import io.ontomato.dataengine.service.AdminService;
import io.ontomato.dataengine.service.BusinessConfigService;
import io.ontomato.dataengine.service.impl.LiveInstanceGraph;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Explicit opt-in: the fixture is provisioned on a separate OMS, never the logistics database. */
@EnabledIfEnvironmentVariable(named = "ONTOMATO_ONN_GRAPH_LIVE_TEST", matches = "true")
class OnnCoreGraphLiveTest {
    private static final String A = "/test/a", B = "/test/b", C = "/test/c", AB = "/test/ab", BC = "/test/bc";
    private DataAdapterConnection connection() {
        DataAdapterConnection c = new DataAdapterConnection(); c.setUrl(System.getenv("ONTOMATO_ONN_LIVE_URL"));
        c.setToken(System.getenv("ONTOMATO_ONN_LIVE_TOKEN")); return c;
    }
    private JSONObject run(OnnCoreAdapter adapter, JSONObject dsl) throws Exception {
        return JSON.parseArray(adapter.executeDsl(JSON.toJSONString(List.of(dsl), com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue),
            null, DslExecutionMode.RAW, new ArrayList<>())).getJSONObject(0);
    }

    @Test void graphBindingsMatchIndependentFixtureWithFiltersCountsAndMultihop() throws Exception {
        OnnCoreAdapter adapter = new OnnCoreAdapter(connection(), null);
        adapter = new OnnCoreAdapter(connection(), adapter.readOntology());
        JSONObject q = OnnCoreGraphCompilerTest.query();
        JSONObject result = run(adapter, q);
        assertEquals(131, result.getJSONArray("answer").size());
        assertTrue(result.getJSONObject("evidence").getBooleanValue("snapshotConsistent"));
        assertTrue(result.getJSONObject("evidence").getBooleanValue("snapshotReleased"));
        assertTrue(result.getJSONArray("answer").stream().map(JSONObject::from).anyMatch(r -> "same:id".equals(r.getString("source")) && "same:id".equals(r.getString("target"))));
        JSONObject first = OnnCoreGraphCompilerTest.pattern(q).getJSONArray("objects").getJSONObject(0);
        first.put("conditions", JSONObject.of("properties", JSONObject.of("field", "id", "operator", "=", "value", "same:id")));
        assertEquals(130, run(adapter, q).getJSONArray("answer").size());
        JSONObject count = OnnCoreGraphCompilerTest.field("a", "id", "paths"); count.put("function", "count");
        JSONObject distinct = OnnCoreGraphCompilerTest.field("a", "id", "objects"); distinct.put("function", "count"); distinct.put("distinct", true);
        OnnCoreGraphCompilerTest.step(q).getJSONObject("output").put("fields", JSONArray.of(count, distinct));
        JSONObject aggregates = run(adapter, q).getJSONArray("answer").getJSONObject(0);
        assertEquals(130, aggregates.getIntValue("paths")); assertEquals(1, aggregates.getIntValue("objects"));
        q = OnnCoreGraphCompilerTest.query();
        JSONObject target = OnnCoreGraphCompilerTest.pattern(q).getJSONArray("objects").getJSONObject(1);
        JSONObject pred = JSONObject.of("field", "when", "operator", "=", "value", "2026-01-15T10:30:00Z");
        target.put("conditions", JSONObject.of("properties", pred));
        assertEquals(44, run(adapter, q).getJSONArray("answer").size());
        pred.put("operator", "is"); pred.put("value", null);
        assertEquals(87, run(adapter, q).getJSONArray("answer").size());
        q = OnnCoreGraphCompilerTest.query();
        OnnCoreGraphCompilerTest.pattern(q).getJSONArray("objects").add(OnnCoreGraphCompilerTest.object(9, "c", C));
        OnnCoreGraphCompilerTest.pattern(q).getJSONArray("relationship").add(OnnCoreGraphCompilerTest.link(3, 9, BC));
        assertEquals(131, run(adapter, q).getJSONArray("answer").size());
        Collections.reverse(OnnCoreGraphCompilerTest.pattern(q).getJSONArray("objects"));
        assertEquals(131, run(adapter, q).getJSONArray("answer").size());
    }

    @Test void managerUsesRealAdapterForOverviewSearchAndBothNeighborDirections() throws Exception {
        Map<String, Object> mapping = new OnnCoreAdapter(connection(), null).readOntology();
        var admin = mock(AdminService.class); var configs = mock(BusinessConfigService.class); var registry = mock(DataAdapterRegistry.class);
        BusinessConfig config = new BusinessConfig(); config.setDataAdapter("onn-core");
        when(admin.getJSONRule("test")).thenReturn(mapping); when(configs.get("test")).thenReturn(config);
        when(registry.create(any(), any())).thenAnswer(call -> new OnnCoreAdapter(connection(), mapping));
        LiveInstanceGraph manager = new LiveInstanceGraph(admin, configs, registry);
        JSONObject summary = manager.query("summary", new JSONObject(), "test");
        assertTrue(summary.getBooleanValue("success"), summary.toJSONString());
        assertEquals(134, summary.getJSONObject("data").getIntValue("nodeCount"));
        assertFalse(summary.getJSONObject("data").getJSONArray("overviewEdges").isEmpty());
        JSONObject search = manager.query("search", JSONObject.of("class", A, "term", "same:id"), "test");
        assertTrue(search.getBooleanValue("success"), search.toJSONString());
        assertEquals(A + ":same:id", search.getJSONObject("data").getJSONArray("nodes").getJSONObject(0).getString("id"));
        JSONObject request = JSONObject.of("id", A + ":same:id", "direction", "outgoing", "relationType", AB);
        JSONObject page1 = manager.query("neighborhood", request, "test");
        assertTrue(page1.getBooleanValue("success"), page1.toJSONString());
        assertEquals(100, page1.getJSONObject("data").getJSONArray("edges").size());
        request.put("offset", page1.getJSONObject("data").getIntValue("nextOffset"));
        JSONObject page2 = manager.query("neighborhood", request, "test");
        assertTrue(page2.getBooleanValue("success"), page2.toJSONString());
        assertEquals(30, page2.getJSONObject("data").getJSONArray("edges").size());
        assertNull(page2.getJSONObject("data").get("nextOffset"));
        JSONObject incoming = manager.query("neighborhood", JSONObject.of("id", B + ":same:id", "direction", "incoming", "relationType", AB), "test");
        assertTrue(incoming.getBooleanValue("success"), incoming.toJSONString());
        assertEquals(1, incoming.getJSONObject("data").getJSONArray("edges").size());
        assertEquals(A + ":same:id", incoming.getJSONObject("data").getJSONArray("edges").getJSONObject(0).getString("source"));
    }
}
