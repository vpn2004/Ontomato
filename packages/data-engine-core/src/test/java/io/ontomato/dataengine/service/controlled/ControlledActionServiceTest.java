package io.ontomato.dataengine.service.controlled;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.ontomato.dataengine.config.BusinessConfig;
import io.ontomato.dataengine.dao.ControlledActionDecisionDao;
import io.ontomato.dataengine.dao.JSONRuleDao;
import io.ontomato.dataengine.dataAdapter.*;
import io.ontomato.dataengine.service.BusinessConfigService;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ControlledActionServiceTest {
    @TempDir Path dir;
    Path file;
    ControlledActionService service;
    ControlledActionPolicy policy;
    BusinessConfig config;
    JSONRuleDao rules;
    ConditionalObjectWrites writer;
    MemoryDecisions ledger;
    final List<JSONObject> calls = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach void setup() throws Exception {
        file = dir.resolve("policy.json");
        Files.writeString(file, """
            {"version":1,"allowSharedVisitors":true,"domainId":"1","backendUrl":"http://onn:18081",
             "subject":"ontomato-actions","roles":["carrier-writer"],"actions":[
             {"id":"carrier-level","name":"Contract level","className":"/logistics/carrier",
              "field":"contract_level","fieldLabel":"Contract level","values":["normal","core","observe"]}]}
            """);
        policy = new ControlledActionPolicy(file.toString());
        var configs = mock(BusinessConfigService.class); rules = mock(JSONRuleDao.class);
        var registry = mock(DataAdapterRegistry.class);
        var adapter = mock(DataAdapter.class, withSettings().extraInterfaces(ConditionalObjectWrites.class));
        writer = (ConditionalObjectWrites) adapter;
        config = new BusinessConfig(); config.setDataAdapter("onn-core");
        var connection = new DataAdapterConnection(); connection.setUrl("http://onn:18081"); connection.setToken("fixture-only");
        config.getDataAdapterConnections().put("onn-core", connection);
        when(configs.get("1")).thenReturn(config);
        when(rules.query("1")).thenReturn(new LinkedHashMap<>(Map.of("schema", "v1")));
        when(registry.create(eq(config), anyMap())).thenReturn(adapter);
        when(writer.readVersionedObject(anyString(), anyString(), any())).thenReturn(
            JSONObject.of("object", JSONObject.of("id", "CR001", "contract_level", "normal"), "version", 7L, "etag", "\"opaque-7\""));
        when(writer.patchObject(anyString(), anyString(), any(), anyString(), anyString(), any())).thenAnswer(i -> {
            calls.add(JSONObject.of("class", i.getArgument(0), "id", i.getArgument(1), "values", i.getArgument(2),
                "etag", i.getArgument(3), "key", i.getArgument(4), "principal", i.getArgument(5)));
            return JSONObject.of("status", "ok", "version", 8L);
        });
        ledger = new MemoryDecisions(); service = new ControlledActionService(policy, ledger, configs, rules, registry);
    }
    JSONObject prepare() throws Exception { return service.prepare("1", "shared-visitor", "carrier-level", "CR001", JSONObject.of("contract_level", "core")); }

    @Test void previewDoesNotWriteAndSuccessfulDecisionReplaysFromDurableEvidence() throws Exception {
        var preview = prepare(); assertEquals("normal", preview.getJSONObject("before").getString("contract_level"));
        assertEquals("PREPARED", preview.getString("state")); assertEquals(0, calls.size());
        assertFalse(preview.containsKey("expectedEtag")); assertFalse(preview.containsKey("contextHash"));
        String id = preview.getString("id");
        assertEquals("SUCCEEDED", service.execute("1", id).getString("state"));
        assertEquals("SUCCEEDED", service.execute("1", id).getString("state")); assertEquals(1, calls.size());
        assertEquals("\"opaque-7\"", calls.get(0).getString("etag"));
        assertEquals("action:" + id, calls.get(0).getString("key"));
        verify(writer, times(1)).readVersionedObject(anyString(), anyString(), any());
    }
    @Test void allowlistRejectsAdditionalFieldsNullAndNonEnumValues() {
        for (JSONObject invalid : List.of(JSONObject.of("id", "CR002"), JSONObject.of("contract_level", "admin"),
            JSONObject.of("contract_level", "core", "name", "spoof"), JSON.parseObject("{\"contract_level\":null}")))
            assertThrows(IllegalArgumentException.class, () -> service.prepare("1", "visitor", "carrier-level", "CR001", invalid));
        assertThrows(IllegalArgumentException.class, () -> service.prepare("1", "visitor", "other", "CR001", JSONObject.of("contract_level", "core")));
        assertTrue(calls.isEmpty());
    }
    @Test void disabledAndForeignDomainsHaveNoActions() throws Exception {
        assertTrue(new ControlledActionPolicy("").load("1").isEmpty());
        assertFalse(service.catalog("other").getBooleanValue("enabled"));
        assertThrows(UnsupportedOperationException.class, () -> service.prepare("other", "visitor", "carrier-level", "CR001", JSONObject.of("contract_level", "core")));
        Files.writeString(file, Files.readString(file).replace("\"allowSharedVisitors\":true", "\"allowSharedVisitors\":false"));
        assertThrows(IllegalStateException.class, () -> policy.load("1"));
    }
    @Test void backendMappingTokenAndPolicyChangesCannotRetargetPreparedDecision() throws Exception {
        String id = prepare().getString("id");
        config.getDataAdapterConnections().get("onn-core").setUrl("http://other:18081");
        assertThrows(IllegalStateException.class, () -> service.execute("1", id));
        config.getDataAdapterConnections().get("onn-core").setUrl("http://onn:18081");
        when(rules.query("1")).thenReturn(Map.of("schema", "v2"));
        assertThrows(IllegalStateException.class, () -> service.execute("1", id));
        when(rules.query("1")).thenReturn(Map.of("schema", "v1"));
        config.getDataAdapterConnections().get("onn-core").setToken("rotated-fixture-token");
        assertThrows(IllegalStateException.class, () -> service.execute("1", id));
        assertTrue(calls.isEmpty());
    }
    @Test void staleVersionAndDeniedWritesAreTerminalAndNeverReadFreshToOverwrite() throws Exception {
        for (int status : List.of(412, 403, 422)) {
            String id = prepare().getString("id");
            doThrow(new ConditionalObjectWrites.Rejected(status, "BACKEND_REJECTION")).when(writer)
                .patchObject(anyString(), anyString(), any(), anyString(), anyString(), any());
            var result = service.execute("1", id);
            assertEquals(status == 412 ? "CONFLICT" : "REJECTED", result.getString("state"));
            assertEquals(result, service.execute("1", id));
        }
        verify(writer, times(3)).readVersionedObject(anyString(), anyString(), any());
        verify(writer, times(3)).patchObject(anyString(), anyString(), any(), eq("\"opaque-7\""), anyString(), any());
    }
    @Test void uncertainCommitRetriesOnlyTheOriginalDecision() throws Exception {
        String id = prepare().getString("id");
        List<String> attempts = new ArrayList<>();
        when(writer.patchObject(anyString(), anyString(), any(), anyString(), anyString(), any())).thenAnswer(i -> {
            attempts.add(i.getArgument(3) + ":" + i.getArgument(4) + ":" + i.getArgument(2));
            if (attempts.size() == 1) throw new java.net.http.HttpTimeoutException("Committed but response lost");
            return JSONObject.of("status", "ok", "version", 8L);
        });
        assertEquals("UNKNOWN", service.execute("1", id).getString("state"));
        assertThrows(IllegalStateException.class, this::prepare);
        assertEquals("SUCCEEDED", service.execute("1", id).getString("state"));
        assertEquals(attempts.get(0), attempts.get(1));
        verify(writer, times(1)).readVersionedObject(anyString(), anyString(), any());
    }
    @Test void concurrentExecuteHasOneClaimAndOneBackendCall() throws Exception {
        String id = prepare().getString("id"); var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
        when(writer.patchObject(anyString(), anyString(), any(), anyString(), anyString(), any())).thenAnswer(i -> {
            entered.countDown(); assertTrue(finish.await(5, TimeUnit.SECONDS)); return JSONObject.of("version", 8L);
        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> service.execute("1", id)); assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals("EXECUTING", service.execute("1", id).getString("state")); finish.countDown();
            assertEquals("SUCCEEDED", first.get(5, TimeUnit.SECONDS).getString("state"));
        } finally { finish.countDown(); pool.shutdownNow(); }
        verify(writer, times(1)).patchObject(anyString(), anyString(), any(), anyString(), anyString(), any());
    }
    @Test void expiredUnknownDecisionCannotBeReissuedAndDomainCannotReadEvidence() throws Exception {
        String id = prepare().getString("id");
        ledger.rows.get("1:" + id).put("createdAt", System.currentTimeMillis() - 31 * 60 * 1000);
        assertThrows(IllegalStateException.class, () -> service.execute("1", id));
        assertThrows(IllegalArgumentException.class, () -> service.status("other", id)); assertTrue(calls.isEmpty());
    }
    static class MemoryDecisions extends ControlledActionDecisionDao {
        final Map<String, JSONObject> rows = new ConcurrentHashMap<>();
        MemoryDecisions() { super(null); }
        @Override public synchronized void insert(String d, JSONObject decision) { var r = JSON.parseObject(decision.toJSONString()); r.put("state", "PREPARED"); rows.put(d + ":" + r.getString("id"), r); }
        @Override public synchronized JSONObject get(String d, String id) { var row = rows.get(d + ":" + id); if (row == null) throw new IllegalArgumentException("Missing"); return JSON.parseObject(row.toJSONString()); }
        @Override public List<String> recent(String d) { return rows.values().stream().map(r -> r.getString("id")).toList(); }
        @Override public synchronized boolean hasUncertain(String d, String cls, String id) { return rows.entrySet().stream().anyMatch(e -> e.getKey().startsWith(d + ":") && Set.of("UNKNOWN", "EXECUTING").contains(e.getValue().getString("state")) && id.equals(e.getValue().getString("objectId")) && cls.equals(e.getValue().getString("className"))); }
        @Override public synchronized boolean claim(String d, String id, String token, long now) { var row = rows.get(d + ":" + id); if (!Set.of("PREPARED", "UNKNOWN").contains(row.getString("state"))) return false; row.put("state", "EXECUTING"); row.put("claim", token); return true; }
        @Override public synchronized void finish(String d, String id, String token, String state, JSONObject result) { var row = rows.get(d + ":" + id); if (token.equals(row.getString("claim"))) { row.put("state", state); row.put("result", result); } }
    }
}
