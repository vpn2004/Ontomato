package io.ontomato.dataengine.dataAdapter;

import com.alibaba.fastjson2.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OnnCoreT30Test {
    static final String CLASS = "/test/item";
    static OnnCoreSchema schema() {
        return new OnnCoreSchema(JSONObject.of("ontologies", JSONArray.of(JSONObject.of("path", CLASS, "attributes", JSONArray.of(
            JSONObject.of("name", "name", "dataType", "string"), JSONObject.of("name", "flag", "dataType", "boolean"),
            JSONObject.of("name", "amount", "dataType", "float"), JSONObject.of("name", "when", "dataType", "datetime")))), "relations", new JSONArray()));
    }
    static JSONObject schemaWire() {
        return JSONObject.of("ontologies", JSONArray.of(JSONObject.of("path", CLASS, "attributes", JSONArray.of(
            JSONObject.of("name", "name", "dataType", "string"), JSONObject.of("name", "flag", "dataType", "boolean"),
            JSONObject.of("name", "amount", "dataType", "float"), JSONObject.of("name", "when", "dataType", "datetime")))), "relations", new JSONArray());
    }
    static JSONObject manifest() {
        JSONObject manifest = OnnCoreCapabilitiesTest.manifest();
        manifest.put("commit", "t30-dirty"); manifest.put("build_time", "2026-11-05T01:30:29Z");
        manifest.getJSONArray("features").getJSONObject(1).getJSONObject("pagination").put("result_kinds", List.of("data", "bindings", "return"));
        for (String id : List.of("pipeline", "graph_query", "graph_binding_aggregate"))
            manifest.getJSONArray("features").add(JSONObject.of("id", id, "enabled", true, "contract_version", "v1"));
        manifest.getJSONArray("features").add(JSONObject.of("id", "query_pushdown", "enabled", true, "contract_version", "v1",
            "query_pushdown", JSONObject.of("contract_version", "v1", "datetime_comparison", "absolute_instant", "null_equality", "null_or_missing",
                "metrics_only_scope", "single_class_identity_count_without_attribute_or_expr", "metrics_only_operators", List.of("count"), "single_page_elision", true)));
        return manifest;
    }
    static JSONObject field(String name, String alias, String fn) {
        JSONObject field = JSONObject.of("variable", "o", "field", name, "as", alias);
        if (fn != null) field.put("function", fn); return field;
    }
    static JSONObject query(JSONArray fields, JSONObject predicate) {
        JSONObject object = JSONObject.of("class", CLASS, "variable", "o");
        if (predicate != null) object.put("conditions", JSONObject.of("properties", predicate));
        return JSONObject.of("answer", JSONObject.of("steps", JSONArray.of(JSONObject.of("graph", JSONObject.of("patterns", JSONArray.of(
            JSONObject.of("objects", JSONArray.of(object)))), "output", JSONObject.of("fields", fields)))));
    }
    static JSONObject predicate(String field, String op, Object value) { return JSONObject.of("field", field, "operator", op, "value", value); }
    private OnnCoreDslCompiler compile(JSONObject query) { return new OnnCoreDslCompiler(query, schema(), schema().ontology()); }

    @Test void newFeaturesRequireTheExplicitKnownEnabledSemanticContract() {
        assertTrue(OnnCoreCapabilities.parse(manifest()).pushdown());
        for (String key : List.of("contract_version", "datetime_comparison", "null_equality", "metrics_only_scope")) {
            JSONObject bad = manifest(); bad.getJSONArray("features").getJSONObject(5).getJSONObject("query_pushdown").put(key, "future");
            assertFalse(OnnCoreCapabilities.parse(bad).pushdown());
        }
        JSONObject disabled = manifest(); disabled.getJSONArray("features").getJSONObject(5).put("enabled", false);
        assertFalse(OnnCoreCapabilities.parse(disabled).pushdown());
    }
    @Test void datesNullAndNegationCompileWithoutAdoptingCoreNullInequality() {
        for (JSONObject p : List.of(predicate("when", "=", "2026-01-15T18:30:00+08:00"), predicate("name", "is", null),
                predicate("name", "!=", "A"), predicate("name", "=", null), predicate("name", "in", Arrays.asList("A", null)))) {
            OnnCoreDslCompiler plan = compile(query(JSONArray.of(field("id", "n", "count")), p));
            assertTrue(plan.nativeMetricsCount(), p.toString());
            assertTrue(plan.nativeSnapshotRead(true).containsKey("where_logic"));
            if (p.getString("operator").equals("!=")) {
                String nativeJson = JSON.toJSONString(plan.nativeSnapshotRead(true), JSONWriter.Feature.WriteMapNullValue);
                assertTrue(nativeJson.contains("\"value\":null"));
                assertTrue(nativeJson.contains("\"value\":\"A\""));
            }
        }
    }
    @Test void numericBoundsAreWidenedAndNeverUsedForExactNativeCount() {
        var plan = compile(query(JSONArray.of(field("id", "n", "count")), predicate("amount", ">", new java.math.BigDecimal("9007199254740993"))));
        assertFalse(plan.nativeMetricsCount()); assertFalse(plan.exactPredicates());
        JSONObject condition = plan.nativeSnapshotRead(true).getJSONObject("where_logic").getJSONArray("children").getJSONObject(0).getJSONArray("conditions").getJSONObject(0);
        assertEquals("gte", condition.getString("operator"));
        assertTrue(condition.getDoubleValue("value") < 9007199254740992d);
        assertFalse(compile(query(JSONArray.of(field("id", "n", "count")), predicate("name", "=", "2026-01-15"))).nativeMetricsCount());
    }
    @Test void exactCountDownloadsNoObjectsAndWarmQueriesUseOneSchemaRevalidation() throws Exception {
        try (Fixture f = new Fixture()) {
            var adapter = f.adapter(null);
            JSONObject dsl = query(JSONArray.of(field("id", "n", "count")), predicate("flag", "=", true));
            for (int i = 0; i < 2; i++) {
                JSONObject result = f.execute(adapter, dsl);
                assertEquals(7, result.getJSONArray("answer").getJSONObject(0).getLongValue("n"));
                assertEquals(0, result.getJSONObject("evidence").getIntValue("objectsRead"));
                assertEquals("metrics", f.requests.getLast().getJSONObject("pagination").getString("result_kind"));
            }
            assertEquals(3, f.schemas.get()); assertEquals(1, f.capabilities.get()); assertEquals(2, f.notModified.get());
        }
    }
    @Test void metadataCannotCrossPrincipalOrCredentialScopes() throws Exception {
        try (Fixture f = new Fixture()) {
            JSONObject dsl = query(JSONArray.of(field("id", "n", "count")), null);
            f.execute(f.adapter(new ConditionalObjectWrites.Principal("a", List.of("read"))), dsl);
            f.execute(f.adapter(new ConditionalObjectWrites.Principal("b", List.of("read"))), dsl);
            assertEquals(4, f.schemas.get()); assertEquals(2, f.capabilities.get());
            assertEquals(2, f.notModified.get());
        }
    }
    @Test void forbiddenAndBudgetErrorsPropagateAndInvalidateMetadata() throws Exception {
        for (int status : List.of(403, 422)) try (Fixture f = new Fixture()) {
            var adapter = f.adapter(null); JSONObject dsl = query(JSONArray.of(field("id", "n", "count")), null);
            f.execute(adapter, dsl); f.queryStatus = status;
            var error = assertThrows(OnnCoreHttpClient.OnnHttpException.class, () -> f.execute(adapter, dsl));
            assertEquals(status, error.status); f.queryStatus = 200; f.execute(adapter, dsl);
            assertEquals(2, f.capabilities.get());
        }
    }
    @Test void schemaDescriptionChangesDuringTheQueryRejectTheAnswer() throws Exception {
        try (Fixture f = new Fixture()) {
            f.changeSchemaAfterQuery = true;
            assertThrows(IllegalStateException.class, () -> f.execute(f.adapter(null), query(JSONArray.of(field("id", "n", "count")), null)));
        }
    }
    @Test void readerAcceptsInlineOnlyWhenRequestedAndValidatesExactMetrics() throws Exception {
        try (Fixture f = new Fixture()) {
            OnnCoreSnapshotReader reader = new OnnCoreSnapshotReader(f.client());
            JSONObject q = JSONObject.of("match_class", CLASS);
            assertEquals(1, reader.readNative(q, 2, "data", false, null, true, true).rows().size());
            assertThrows(IllegalStateException.class, () -> reader.read(q, 2));
            f.badCount = true;
            assertThrows(IllegalStateException.class, () -> reader.readNative(JSONObject.of("match_class", CLASS, "metrics", List.of(JSONObject.of("name", "total", "type", "count"))), 1, "metrics", false, null, true, false));
        }
    }
    @Test void globalCountReturnRetainsWindowsAndAttributeNonNullCount() throws Exception {
        try (Fixture f = new Fixture()) {
            JSONObject dsl = query(JSONArray.of(field("name", "names", "count")), null);
            JSONObject result = f.execute(f.adapter(null), dsl);
            assertEquals(3, result.getJSONArray("answer").getJSONObject(0).getLongValue("names"));
            assertEquals("return", result.getJSONObject("evidence").getString("resultKind"));
            assertEquals("paths", f.requests.getLast().getJSONObject("return").getJSONArray("metrics").getJSONObject(0).getString("over"));
        }
    }
    @Test void unsafeStringGroupingAndDecimalAggregatesKeepTheResidualPlan() {
        for (String function : List.of("sum", "avg", "min", "max")) {
            JSONObject dsl = query(JSONArray.of(field("amount", "a", function)), null);
            assertNull(OnnCoreAggregatePlan.compile(dsl, schema(), Map.of("o", CLASS)));
        }
        JSONObject dsl = query(JSONArray.of(field("name", "name", null), field("id", "n", "count")), null);
        dsl.getJSONObject("answer").getJSONArray("steps").getJSONObject(0).getJSONObject("output")
            .put("group_by", JSONObject.of("fields", List.of(JSONObject.of("variable", "o", "field", "name"))));
        assertNull(OnnCoreAggregatePlan.compile(dsl, schema(), Map.of("o", CLASS)));
    }
    static final class Fixture implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final AtomicInteger schemas = new AtomicInteger(), capabilities = new AtomicInteger(), notModified = new AtomicInteger();
        final List<JSONObject> requests = new ArrayList<>();
        int queryStatus = 200; boolean badCount, changeSchemaAfterQuery, changed;
        Fixture() throws Exception {
            server.createContext("/api/v1/", exchange -> {
                int status = 200; JSONObject body;
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/schema/all")) {
                    schemas.incrementAndGet(); String tag = changed ? "\"changed\"" : "\"schema\"";
                    exchange.getResponseHeaders().set("ETag", tag);
                    if (tag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) { notModified.incrementAndGet(); exchange.sendResponseHeaders(304, -1); exchange.close(); return; }
                    body = schemaWire(); if (changed) body.getJSONArray("ontologies").getJSONObject(0).put("description", "changed");
                } else if (path.endsWith("/system/capabilities")) { capabilities.incrementAndGet(); body = manifest(); }
                else {
                    JSONObject query = JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); requests.add(query);
                    String kind = query.getJSONObject("pagination").getString("result_kind");
                    long total = kind.equals("metrics") ? 7 : kind.equals("return") ? 0 : 1;
                    body = OnnCoreSnapshotReaderTest.page(new JSONArray(), total, null, null, 17);
                    JSONObject meta = body.getJSONObject("pagination"); meta.put("result_kind", kind);
                    if (kind.equals("metrics")) body.put("metrics", JSONObject.of("total", badCount ? 8 : 7));
                    else if (kind.equals("return")) {
                        body.remove("data"); JSONObject metrics = new JSONObject();
                        for (Object raw : query.getJSONObject("return").getJSONArray("metrics")) metrics.put(JSONObject.from(raw).getString("name"), 3);
                        body.put("return", JSONObject.of("rows", new JSONArray(), "metrics", metrics));
                    } else { body.put("data", JSONArray.of(JSONObject.of("id", "a", "classPath", CLASS, "attributes", JSONObject.of("name", "A")))); meta.put("page_count", 1); }
                    body.put("query_profile", JSONObject.of("read_keys", 4));
                    status = queryStatus; if (status != 200) body = JSONObject.of("code", status == 403 ? "FIELD_FORBIDDEN" : "QUERY_LIMIT_EXCEEDED");
                    if (changeSchemaAfterQuery) changed = true;
                }
                byte[] bytes = JSON.toJSONBytes(body, JSONWriter.Feature.WriteMapNullValue); exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            }); server.start();
        }
        OnnCoreHttpClient client() { return new OnnCoreHttpClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-gateway"); }
        OnnCoreAdapter adapter(ConditionalObjectWrites.Principal principal) {
            DataAdapterConnection connection = new DataAdapterConnection(); connection.setUrl(client().baseUrl()); connection.setToken("test-gateway");
            return new OnnCoreAdapter(connection, schema().ontology(), principal);
        }
        JSONObject execute(OnnCoreAdapter adapter, JSONObject dsl) throws Exception {
            return JSON.parseArray(adapter.executeDsl(JSON.toJSONString(List.of(dsl), JSONWriter.Feature.WriteMapNullValue), null, DslExecutionMode.OPTIMIZED, new ArrayList<>())).getJSONObject(0);
        }
        public void close() { server.stop(0); }
    }
}
