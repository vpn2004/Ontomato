package io.ontomato.dataengine.dataAdapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.ontomato.dataengine.dataAdapter.DslExecutionMode;

/** Exercises the adapter entry point against synthetic responses on an ephemeral loopback port. */
class OnnCoreAdapterTest {
    private static final String CLASS = "/test/item";
    private static final String SCHEMA = "/api/v1/schema/all";
    private static final String SAMPLE = "/api/v1/data/sample";
    private static final String QUERY = "/api/v1/query/execute";
    private static final String INSTANCE = "/api/v1/data/instance";

    @Test
    void healthyBoundedClassChecksCountsBeforeAndAfterNativeRead() throws Exception {
        try (Fixture fixture = new Fixture()) {
            List<String> generated = new ArrayList<>();
            JSONObject result = execute(fixture.adapter(), query(fields(field("id", "id"), field("name", "name")), null), generated);
            JSONArray answer = result.getJSONArray("answer");
            assertEquals(2, answer.size());
            assertEquals("a", answer.getJSONObject(0).getString("id"));
            assertEquals("Alpha", answer.getJSONObject(0).getString("name"));
            assertEquals("b", answer.getJSONObject(1).getString("id"));
            assertEquals(List.of("GET " + SCHEMA, "GET " + SAMPLE, "POST " + QUERY, "GET " + SAMPLE), fixture.operations());
            for (Request sample : fixture.requests(SAMPLE)) {
                assertEquals(Map.of("class", CLASS, "limit", "1"), parameters(sample.query()));
            }
            Request request = fixture.requests(QUERY).get(0);
            JSONObject nativeQuery = JSON.parseObject(request.body());
            assertEquals(CLASS, nativeQuery.getString("match_class"));
            assertEquals(5001, nativeQuery.getIntValue("limit"));
            assertEquals("count", nativeQuery.getJSONArray("metrics").getJSONObject(0).getString("type"));
            assertEquals("total", nativeQuery.getJSONArray("metrics").getJSONObject(0).getString("name"));
            assertFalse(nativeQuery.containsKey("where"));
            assertFalse(nativeQuery.containsKey("traverse"));
            assertEquals(4, generated.size());
            assertEquals("GET " + SCHEMA, generated.get(0));
            assertEquals("GET " + SAMPLE + "?class=%2Ftest%2Fitem&limit=1", generated.get(1));
            assertTrue(generated.get(2).startsWith("POST " + QUERY + " "));
            assertEquals(generated.get(1), generated.get(3));
            assertEquals(generated, result.getJSONArray("mqls").toJavaList(String.class));
            JSONObject evidence = result.getJSONObject("evidence");
            assertEquals("onn-core", evidence.getString("backend"));
            assertEquals("BASE", evidence.getString("view"));
            assertEquals(2, evidence.getIntValue("objectsRead"));
            assertFalse(evidence.getBooleanValue("snapshotConsistent"));
        }
    }

    @Test
    void rejectsAnOmittedInstanceEvenWhenNativeMetricsMatchReturnedRows() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.queryResponse = queryResponse(array(instance("a", "Alpha")), 1, 1);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> execute(fixture.adapter(), objectQuery(), new ArrayList<>()));
            assertTrue(failure.getMessage().contains("omitted"));
            assertEquals(2, fixture.requests(SAMPLE).size());
            assertEquals(1, fixture.requests(QUERY).size());
        }
    }

    @Test
    void rejectsMatchedCountAndMetricCountMismatches() throws Exception {
        for (boolean matchedCountMismatch : List.of(true, false)) {
            try (Fixture fixture = new Fixture()) {
                fixture.queryResponse = queryResponse(array(instance("a", "Alpha")), matchedCountMismatch ? 2 : 1, matchedCountMismatch ? 1 : 2);
                IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> execute(fixture.adapter(), objectQuery(), new ArrayList<>()));
                assertTrue(failure.getMessage().contains(matchedCountMismatch ? "consistent" : "incomplete"));
                assertEquals(1, fixture.requests(QUERY).size());
                assertEquals(1, fixture.requests(SAMPLE).size(), "An inconsistent native response must not trigger a second count read");
            }
        }
    }

    @Test
    void rejectsMissingNativeCountMetadata() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.queryResponse.remove("metrics");
            assertThrows(IllegalStateException.class, () -> execute(fixture.adapter(), objectQuery(), new ArrayList<>()));
            assertEquals(1, fixture.requests(SAMPLE).size());
        }
    }

    @Test
    void rejectsFractionalAndOverflowingMatchedCountsInsteadOfTruncatingThem() throws Exception {
        for (Object invalid : List.of(new java.math.BigDecimal("2.5"), 4294967298L, -1)) {
            try (Fixture fixture = new Fixture()) {
                fixture.queryResponse.put("matched_count", invalid);
                assertThrows(IllegalStateException.class, () -> execute(fixture.adapter(), objectQuery(), new ArrayList<>()));
                assertEquals(1, fixture.requests(SAMPLE).size());
            }
        }
    }

    @Test
    void rejectsDuplicateIdentitiesAndWrongClassResponses() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.queryResponse = queryResponse(array(instance("a", "Alpha"), instance("a", "Another value")), 2, 2);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> execute(fixture.adapter(), objectQuery(), new ArrayList<>()));
            assertTrue(failure.getMessage().contains("duplicate"));
            assertEquals(1, fixture.requests(SAMPLE).size());
        }
        try (Fixture fixture = new Fixture()) {
            JSONObject wrongClass = instance("b", "Beta"); wrongClass.put("classPath", "/test/other");
            fixture.queryResponse = queryResponse(array(instance("a", "Alpha"), wrongClass), 2, 2);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> execute(fixture.adapter(), objectQuery(), new ArrayList<>()));
            assertTrue(failure.getMessage().contains("mismatched"));
            assertEquals(1, fixture.requests(SAMPLE).size());
        }
    }

    @Test
    void rejectsOversizedClassBeforeMakingANativeQuery() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.sampleCounts = new long[] {5001};
            assertThrows(UnsupportedOperationException.class, () -> execute(fixture.adapter(), objectQuery(), new ArrayList<>()));
            assertEquals(List.of("GET " + SCHEMA, "GET " + SAMPLE), fixture.operations());
        }
    }

    @Test
    void rejectsAClassCountThatChangesAcrossTheRead() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.sampleCounts = new long[] {2, 3};
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> execute(fixture.adapter(), objectQuery(), new ArrayList<>()));
            assertTrue(failure.getMessage().contains("changed"));
            assertEquals(2, fixture.requests(SAMPLE).size());
            assertEquals(1, fixture.requests(QUERY).size());
        }
    }

    @Test
    void exactColonIdentityUsesGetAndPreservesTheEntireId() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String id = "asset:zone:42";
            fixture.instanceResponse = instance(id, "Exact object");
            OnnCoreAdapter adapter = fixture.adapter();
            List<String> generated = new ArrayList<>();
            JSONObject result = execute(adapter, query(fields(field("id", "id"), field("name", "name")), predicate("id", "=", id)), generated);
            assertEquals(id, result.getJSONArray("answer").getJSONObject(0).getString("id"));
            assertEquals("Exact object", result.getJSONArray("answer").getJSONObject(0).getString("name"));
            assertEquals(List.of("GET " + SCHEMA, "GET " + INSTANCE), fixture.operations());
            Request request = fixture.requests(INSTANCE).get(0);
            assertEquals(Map.of("class_path", CLASS, "id", id), parameters(request.query()));
            assertTrue(request.query().contains("asset%3Azone%3A42"));
            assertEquals(2, generated.size());
            assertEquals("GET " + SCHEMA, generated.get(0));
            assertTrue(generated.get(1).startsWith("GET " + INSTANCE + "?"));
            String nodeReference = adapter.encodeNodeReference(CLASS, id);
            assertEquals(id, adapter.decodeNodeReference(CLASS, nodeReference));
            assertEquals("id", adapter.objectPrimaryKey(Map.of("attrs", List.of(Map.of("name", "businessKey", "primaryKey", true)))));
        }
    }

    @Test
    void exactMissingIdentityReturnsCountZeroWithoutANativeQuery() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.instanceStatus = 404;
            JSONObject count = field("id", "count"); count.put("function", "count");
            JSONObject result = execute(fixture.adapter(), query(fields(count), predicate("id", "=", "absent:42")), new ArrayList<>());
            assertEquals(0L, result.getJSONArray("answer").getJSONObject(0).getLongValue("count"));
            assertEquals(List.of("GET " + SCHEMA, "GET " + INSTANCE), fixture.operations());
            assertEquals(0, fixture.requests(QUERY).size());
            assertEquals(0, fixture.requests(SAMPLE).size());
        }
    }

    @Test
    void wrongIdentityFromAnExactGetIsRejected() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.instanceResponse = instance("another:id", "Wrong object");
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> execute(fixture.adapter(), query(fields(field("id", "id")), predicate("id", "=", "requested:id")), new ArrayList<>()));
            assertTrue(failure.getMessage().contains("mismatched instance ID"));
            assertEquals(List.of("GET " + SCHEMA, "GET " + INSTANCE), fixture.operations());
        }
    }

    @Test
    void writesSandboxVectorsAndGraphReadsRefuseWithoutAnyHttp() throws Exception {
        try (Fixture fixture = new Fixture()) {
            OnnCoreAdapter adapter = fixture.adapter();
            List<Executable> forbidden = List.of(
                () -> adapter.createNamespace("ns"), () -> adapter.truncateNamespace("ns"), () -> adapter.dropNamespace("ns"),
                () -> adapter.createClass("ns", Map.of()), () -> adapter.createEdgeType("ns", "edge"),
                () -> adapter.insertObjects("ns", Map.of(), new JSONArray()),
                () -> adapter.updateObjects("ns", Map.of(), new JSONObject(), new JSONObject()),
                () -> adapter.deleteObjects("ns", Map.of(), new JSONObject()),
                () -> adapter.createEdge("ns", "edge", CLASS, "a", CLASS, "b"),
                () -> adapter.deleteEdge("ns", "edge", CLASS, "a", CLASS, "b"),
                () -> adapter.createSchemaClass(Map.of(), null), () -> adapter.deleteSchemaClass(CLASS, null),
                () -> adapter.createSchemaRelationship("edge", Map.of(), null),
                () -> adapter.deleteSchemaRelationship("edge", Map.of(), null),
                () -> adapter.addSchemaAttribute(CLASS, Map.of(), null), () -> adapter.deleteSchemaAttribute(CLASS, "name", null),
                () -> adapter.sandboxClassName("sandbox", CLASS), () -> adapter.sandboxObjectId("sandbox", "a"),
                () -> adapter.query(objectQuery(), "sandbox", "domain", null, null),
                () -> adapter.queryVectorAttr(Map.of(), "embedding", "a"),
                () -> adapter.capabilities().requireGraphRead(), () -> adapter.queryNeighbors(CLASS, "a"));
            for (Executable action : forbidden) assertThrows(UnsupportedOperationException.class, action);
            assertFalse(adapter.useM3());
            assertFalse(adapter.capabilities().graphRead());
            assertTrue(adapter.capabilities().query());
            assertTrue(adapter.capabilities().schemaRead());
            assertEquals(List.of(), fixture.operations());
        }
    }

    @Test
    void invalidImportedSourceNameRejectsBeforeAnyBusinessDataReads() throws Exception {
        try (Fixture fixture = new Fixture()) {
            JSONObject imported = JSON.parseObject(JSON.toJSONString(new OnnCoreSchema(fixture.schemaResponse).ontology()));
            JSONArray attrs = imported.getJSONArray("classDef").getJSONObject(0).getJSONArray("attrs");
            for (Object raw : attrs) {
                JSONObject attribute = JSONObject.from(raw);
                if ("name".equals(attribute.getString("name"))) attribute.put("sourceName", "anotherSource");
            }
            OnnCoreAdapter adapter = fixture.adapter(imported);
            assertThrows(IllegalStateException.class, () -> execute(adapter, query(fields(field("name", "name")), null), new ArrayList<>()));
            assertEquals(List.of("GET " + SCHEMA), fixture.operations());
            assertEquals(0, fixture.requests(QUERY).size());
            assertEquals(0, fixture.requests(SAMPLE).size());
            assertEquals(0, fixture.requests(INSTANCE).size());
        }
    }

    @Test
    void sampleRequiresAnImportedClassAndVirtualIdentityBeforeReadingBusinessData() throws Exception {
        try (Fixture fixture = new Fixture()) {
            IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> fixture.adapter(null).querySampleDataByClassName(CLASS, List.of("name")));
            assertTrue(missing.getMessage().contains("Import the ONN schema"));
            assertEquals(List.of("GET " + SCHEMA), fixture.operations());
        }
        try (Fixture fixture = new Fixture()) {
            JSONObject mapping = JSONObject.of("classDef", new JSONArray());
            IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> fixture.adapter(mapping).querySampleDataByClassName(CLASS, List.of("name")));
            assertTrue(missing.getMessage().contains("Import the ONN schema"));
            assertEquals(List.of("GET " + SCHEMA), fixture.operations());
        }
        try (Fixture fixture = new Fixture()) {
            JSONObject mapping = JSON.parseObject(JSON.toJSONString(new OnnCoreSchema(fixture.schemaResponse).ontology()));
            mapping.getJSONArray("classDef").getJSONObject(0).getJSONArray("attrs").remove(0);
            IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> fixture.adapter(mapping).querySampleDataByClassName(CLASS, List.of("name")));
            assertTrue(missing.getMessage().contains("re-import schema"));
            assertEquals(List.of("GET " + SCHEMA), fixture.operations());
        }
    }

    @Test
    void sampleFiltersExplicitlyDisabledFieldsWhilePreservingTheVirtualIdentity() throws Exception {
        try (Fixture fixture = new Fixture()) {
            JSONObject mapping = JSON.parseObject(JSON.toJSONString(new OnnCoreSchema(fixture.schemaResponse).ontology()));
            mapping.getJSONArray("classDef").getJSONObject(0).getJSONArray("attrs").getJSONObject(1).put("enable", false);
            JSONObject sample = JSON.parseObject(fixture.adapter(mapping).querySampleDataByClassName(CLASS, List.of("name")));
            assertEquals(JSONObject.of("id", "a"), sample);
            assertEquals(List.of("GET " + SCHEMA, "GET " + SAMPLE), fixture.operations());
        }
    }

    @Test
    void sampleRejectsSourceMappingDriftInsteadOfSilentlyDroppingTheField() throws Exception {
        try (Fixture fixture = new Fixture()) {
            JSONObject mapping = JSON.parseObject(JSON.toJSONString(new OnnCoreSchema(fixture.schemaResponse).ontology()));
            mapping.getJSONArray("classDef").getJSONObject(0).getJSONArray("attrs").getJSONObject(1).put("sourceName", "wrongSource");
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> fixture.adapter(mapping).querySampleDataByClassName(CLASS, List.of("name")));
            assertTrue(failure.getCause().getMessage().contains("mapping changed"));
            assertEquals(List.of("GET " + SCHEMA, "GET " + SAMPLE), fixture.operations());
        }
    }

    private static JSONObject execute(OnnCoreAdapter adapter, JSONObject dsl, List<String> generated) throws Exception {
        return JSON.parseArray(adapter.executeDsl(JSON.toJSONString(List.of(dsl)), null, DslExecutionMode.OPTIMIZED, generated)).getJSONObject(0);
    }
    private static JSONObject objectQuery() { return query(fields(field("id", "id")), null); }
    private static JSONObject query(JSONArray fields, JSONObject predicate) {
        JSONObject object = JSONObject.of("class", CLASS, "variable", "o");
        if (predicate != null) object.put("conditions", JSONObject.of("properties", predicate));
        return JSONObject.of("answer", JSONObject.of("steps", array(JSONObject.of("graph", JSONObject.of("patterns",
            array(JSONObject.of("objects", array(object), "relationships", new JSONArray()))),
            "output", JSONObject.of("fields", fields, "to_user", true)))));
    }
    private static JSONObject field(String name, String alias) { return JSONObject.of("variable", "o", "field", name, "as", alias); }
    private static JSONObject predicate(String name, String operator, Object value) { return JSONObject.of("field", name, "operator", operator, "value", value); }
    private static JSONArray fields(JSONObject... values) { return array((Object[]) values); }
    private static JSONObject instance(String id, String name) { return JSONObject.of("id", id, "classPath", CLASS, "attributes", JSONObject.of("name", name)); }
    private static JSONObject queryResponse(JSONArray data, int matchedCount, long total) { return JSONObject.of("matched_count", matchedCount, "data", data, "metrics", JSONObject.of("total", total)); }
    private static JSONArray array(Object... values) { JSONArray result = new JSONArray(); result.addAll(Arrays.asList(values)); return result; }
    private static Map<String, String> parameters(String query) {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            parameters.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return parameters;
    }

    private record Request(String method, String path, String query, String body) { }

    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final List<Request> requests = new CopyOnWriteArrayList<>();
        private final AtomicInteger sampleReads = new AtomicInteger();
        private final JSONObject schemaResponse = JSONObject.of("ontologies", array(JSONObject.of("path", CLASS, "name", "Item", "attributes",
            array(JSONObject.of("name", "name", "dataType", "string")))), "relations", new JSONArray());
        private volatile long[] sampleCounts = {2, 2};
        private volatile JSONObject queryResponse = queryResponse(array(instance("b", "Beta"), instance("a", "Alpha")), 2, 2);
        private volatile JSONObject instanceResponse = instance("a", "Alpha");
        private volatile int instanceStatus = 200;

        Fixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::respond);
            server.start();
        }
        OnnCoreAdapter adapter() { return adapter(new OnnCoreSchema(schemaResponse).ontology()); }
        OnnCoreAdapter adapter(Map<String, Object> mapping) {
            DataAdapterConnection connection = new DataAdapterConnection();
            connection.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
            return new OnnCoreAdapter(connection, mapping);
        }
        List<Request> requests(String path) { return requests.stream().filter(request -> path.equals(request.path())).toList(); }
        List<String> operations() { return requests.stream().map(request -> request.method() + " " + request.path()).toList(); }
        private void respond(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Request(exchange.getRequestMethod(), path, exchange.getRequestURI().getRawQuery(), body));
            JSONObject response; int status = 200;
            switch (path) {
                case SCHEMA -> response = schemaResponse;
                case SAMPLE -> {
                    long[] counts = sampleCounts;
                    long count = counts[Math.min(sampleReads.getAndIncrement(), counts.length - 1)];
                    response = JSONObject.of("class", CLASS, "total_count", count, "samples", array(instance("a", "Alpha")));
                }
                case QUERY -> response = queryResponse;
                case INSTANCE -> { status = instanceStatus; response = status == 404 ? JSONObject.of("error", "not found") : instanceResponse; }
                default -> { status = 404; response = JSONObject.of("error", "unexpected route"); }
            }
            byte[] bytes = JSON.toJSONBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
            finally { exchange.close(); }
        }
        public void close() { server.stop(0); }
    }
}
