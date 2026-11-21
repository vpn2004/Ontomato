package io.ontomato.dataengine.dataAdapter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OnnCoreT34TimingTest {
    private static final Duration REMAINING = Duration.ofSeconds(2);
    private HttpServer server;
    private ExecutorService executor;
    private String url;

    @BeforeEach void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test void pageSchema304AndBothClosePathsHaveSeparateMeasuredBoundaries() throws Exception {
        String page = "{\"bindings\":[],\"note\":\"中文\"}";
        String schema = "{\"classes\":[]}";
        server.createContext("/api/v1/query/execute", exchange -> respond(exchange, 200, page));
        server.createContext("/api/v1/schema/all", exchange -> {
            exchange.getResponseHeaders().set("ETag", "\"schema-1\"");
            if (exchange.getRequestHeaders().containsKey("If-None-Match")) empty(exchange, 304);
            else respond(exchange, 200, schema);
        });
        server.createContext("/api/v1/query/windows/close", exchange -> empty(exchange, 204));
        server.createContext("/api/v1/query/snapshots/", exchange -> empty(exchange, 204));
        var timing = new OnnCoreTiming();
        var client = new OnnCoreHttpClient(url).withTiming(timing);
        client.page(JSONObject.of("match_class", "/private-class"), REMAINING, false, true);
        var first = client.schema(null);
        assertFalse(first.notModified());
        double schemaDecode = timing.phases().getDoubleValue("schema.responseDecode");
        assertTrue(client.schema(first.etag()).notModified());
        assertEquals(schemaDecode, timing.phases().getDoubleValue("schema.responseDecode"));
        client.closeWindow("private-close-token", REMAINING);
        client.closeSnapshot("private-snapshot-id", REMAINING);
        assertOperation(timing, "page", 1, 0, bytes(page));
        assertOperation(timing, "schema", 2, 0, bytes(schema));
        assertOperation(timing, "close", 2, 0, 0);
        assertPhase(timing, "page.responseDecode");
        assertPhase(timing, "schema.responseDecode");
        assertFalse(timing.phases().containsKey("close.responseDecode"));
        for (String phase : List.of("requestTotal", "httpExchange", "responseDecode")) assertPhase(timing, phase);
        assertEquals(timing.phases().getDoubleValue("requestTotal"),
            timing.phases().getDoubleValue("page.requestTotal") + timing.phases().getDoubleValue("schema.requestTotal")
                + timing.phases().getDoubleValue("close.requestTotal"), 0.000001);
    }

    @Test void httpAndJsonFailuresCountBytesAndFailuresWithoutSuccess() {
        for (String operation : List.of("page", "schema", "close")) {
            String route = switch (operation) {
                case "page" -> "/api/v1/query/pipeline";
                case "schema" -> "/api/v1/schema/all";
                default -> "/api/v1/query/windows/close";
            };
            String body = operation.equals("schema") ? "not-json" : "{\"code\":\"FORBIDDEN\",\"secret\":\"private-response\"}";
            server.createContext(route, exchange -> respond(exchange, operation.equals("schema") ? 200 : 403, body));
            var timing = new OnnCoreTiming();
            var client = new OnnCoreHttpClient(url).withTiming(timing);
            assertThrows(IllegalStateException.class, () -> {
                switch (operation) {
                    case "page" -> client.pipelinePage(new JSONObject(), REMAINING);
                    case "schema" -> client.schema(null);
                    default -> client.closeWindow("private-close", REMAINING);
                }
            });
            assertOperation(timing, operation, 0, 1, bytes(body));
        }
    }

    @Test void invalidSchema304AndCloseAcknowledgementAreMeasuredFailures() {
        server.createContext("/api/v1/schema/all", exchange -> {
            exchange.getResponseHeaders().set("ETag", "\"changed\"");
            empty(exchange, 304);
        });
        server.createContext("/api/v1/query/windows/close", exchange -> respond(exchange, 200, "{}"));
        var timing = new OnnCoreTiming();
        var client = new OnnCoreHttpClient(url).withTiming(timing);
        assertThrows(IllegalStateException.class, () -> client.schema("\"original\""));
        assertThrows(IllegalStateException.class, () -> client.closeWindow("private-close", REMAINING));
        assertOperation(timing, "schema", 0, 1, 0);
        assertOperation(timing, "close", 0, 1, 2);
    }

    @Test void exchangeIncludesBodyReceptionAndRecordsTimeoutAndReceivedBytes() throws Exception {
        var bodyStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        String prefix = "{\"description\":\"";
        server.createContext("/api/v1/schema/all", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                body.write(prefix.getBytes(StandardCharsets.UTF_8));
                body.flush();
                bodyStarted.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                body.write("done\"}".getBytes(StandardCharsets.UTF_8));
            } catch (IOException expectedAfterCancellation) { }
        });
        var timing = new OnnCoreTiming();
        var client = new OnnCoreHttpClient(url, Duration.ofMillis(400), 1024).withTiming(timing);
        try {
            assertThrows(HttpTimeoutException.class, client::schema);
            assertTrue(bodyStarted.await(1, TimeUnit.SECONDS));
            assertOperation(timing, "schema", 0, 1, bytes(prefix));
            assertTrue(timing.phases().getDoubleValue("schema.httpExchange") >= 200);
            assertFalse(timing.phases().containsKey("schema.responseDecode"));
        } finally { release.countDown(); }
    }

    @Test void oversizedBodyCountsReceivedBytesAndFailedExchange() {
        String body = "{\"private-value\":\"" + "x".repeat(1024) + "\"}";
        server.createContext("/api/v1/schema/all", exchange -> respond(exchange, 200, body));
        var timing = new OnnCoreTiming();
        var client = new OnnCoreHttpClient(url, REMAINING, 128).withTiming(timing);
        assertThrows(IllegalStateException.class, client::schema);
        assertOperation(timing, "schema", 0, 1, bytes(body));
        assertFalse(timing.phases().containsKey("schema.responseDecode"));
    }

    @Test void failedRequestEncodingStillRecordsTotalAndFailureWithoutExchange() {
        var timing = new OnnCoreTiming();
        var client = new OnnCoreHttpClient("http://secret:private-password@example.invalid").withTiming(timing);
        assertThrows(IllegalArgumentException.class, client::schema);
        assertPhase(timing, "schema.requestEncode");
        assertPhase(timing, "schema.requestTotal");
        assertEquals(1, timing.counters().getLongValue("schema.failure"));
        assertFalse(timing.phases().containsKey("schema.httpExchange"));
    }

    @Test void acceptedSnapshot404AndAbsentInstanceKeepTheirExistingReturnSemantics() throws Exception {
        server.createContext("/api/v1/query/snapshots/", exchange -> respond(exchange, 404, "not-json"));
        server.createContext("/api/v1/data/instance", exchange -> respond(exchange, 404, "{\"code\":\"NOT_FOUND\"}"));
        var timing = new OnnCoreTiming();
        var client = new OnnCoreHttpClient(url).withTiming(timing);
        client.closeSnapshot("private-snapshot-id", REMAINING);
        assertNull(client.instance("/private-class", "private-id"));
        assertOperation(timing, "close", 1, 0, bytes("not-json"));
        assertOperation(timing, "instance", 0, 1, bytes("{\"code\":\"NOT_FOUND\"}"));
        var before = timing.counters();
        client.closeSnapshot(null, REMAINING);
        client.closeSnapshot(" ", REMAINING);
        assertEquals(before, timing.counters());
        assertFalse(timing.phases().containsKey("close.responseDecode"));
    }

    @Test void fixedOperationMetricsStayClientLocalAndNeverContainCredentialsOrBusinessValues() throws Exception {
        server.createContext("/api/v1/", exchange -> respond(exchange, 200, "{\"private-response\":\"客户资料\"}"));
        var first = new OnnCoreTiming();
        var second = new OnnCoreTiming();
        var base = new OnnCoreHttpClient(url, "private-gateway-token");
        var measured = base.withTiming(first);
        measured.capabilities();
        measured.instance("/private-class", "private-id");
        measured.sample("/private-class");
        measured.query(JSONObject.of("private-query", "客户资料"));
        measured.asPrincipal(new ConditionalObjectWrites.Principal("private-subject", List.of("private-role")))
            .patch("/private-class", "private-id", JSONObject.of("value", "客户资料"), "private-etag", "private-idempotency");
        var firstPhases = first.phases();
        var firstCounters = first.counters();
        base.withTiming(second).schema();
        base.schema();
        assertEquals(firstPhases, first.phases());
        assertEquals(firstCounters, first.counters());
        assertEquals(1, first.counters().getLongValue("capabilities.success"));
        assertEquals(2, first.counters().getLongValue("instance.success"));
        assertEquals(2, first.counters().getLongValue("other.success"));
        assertEquals(Set.of("schema"), second.counters().keySet().stream()
            .filter(key -> key.contains(".")).map(key -> key.split("\\.")[0]).collect(java.util.stream.Collectors.toSet()));
        for (String key : first.phases().keySet()) assertTrue(key.matches("(?:(?:page|schema|close|capabilities|instance|other)\\.)?(?:requestEncode|httpExchange|responseDecode|requestTotal)"), key);
        for (String key : first.counters().keySet()) assertTrue(key.matches("(?:(?:page|schema|close|capabilities|instance|other)\\.(?:success|failure|responseBytes)|httpSuccess|httpFailure|responseBytes)"), key);
        String metrics = JSON.toJSONString(JSONObject.of("phases", first.phases(), "counters", first.counters()));
        for (String secret : List.of(url, "private-", "客户资料", "Authorization", "Bearer", "query")) assertFalse(metrics.contains(secret), metrics);
    }

    @Test void windowMeasuresValidationAndBudgetEncodingOnSuccessAndValidationFailure() throws Exception {
        try (var fixture = new OnnCoreT33WindowTest.Fixture()) {
            var timing = new OnnCoreTiming();
            var reader = new OnnCoreWindowReader(fixture.client.withTiming(timing));
            assertTrue(reader.read(JSONObject.of("steps", new com.alibaba.fastjson2.JSONArray()), 2, 3, Map.of("p", "/p")).released());
            assertPhase(timing, "budgetEncode");
            assertPhase(timing, "pageValidation");
            assertEquals(2, timing.counters().getLongValue("page.success"));
            assertEquals(1, timing.counters().getLongValue("close.success"));
            double validation = timing.phases().getDoubleValue("pageValidation");
            double budget = timing.phases().getDoubleValue("budgetEncode");
            fixture.fault = "ordering";
            assertThrows(IllegalStateException.class,
                () -> reader.read(JSONObject.of("steps", new com.alibaba.fastjson2.JSONArray()), 2, 3, Map.of("p", "/p")));
            assertTrue(timing.phases().getDoubleValue("pageValidation") > validation);
            assertEquals(budget, timing.phases().getDoubleValue("budgetEncode"));
            assertEquals(2, timing.counters().getLongValue("close.success"));
        }
    }

    private static void assertOperation(OnnCoreTiming timing, String operation, long success, long failure, long responseBytes) {
        assertEquals(success, timing.counters().getLongValue(operation + ".success"), operation + " success");
        assertEquals(failure, timing.counters().getLongValue(operation + ".failure"), operation + " failure");
        assertTrue(timing.counters().containsKey(operation + ".responseBytes"));
        assertEquals(responseBytes, timing.counters().getLongValue(operation + ".responseBytes"), operation + " bytes");
        for (String phase : List.of("requestEncode", "httpExchange", "requestTotal")) assertPhase(timing, operation + "." + phase);
    }

    private static void assertPhase(OnnCoreTiming timing, String phase) {
        assertTrue(timing.phases().containsKey(phase), phase + " missing: " + timing.phases());
        assertTrue(Double.isFinite(timing.phases().getDoubleValue(phase)));
        assertTrue(timing.phases().getDoubleValue(phase) >= 0);
    }

    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }

    private static void empty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
