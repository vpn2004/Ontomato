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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class OnnCoreHttpClientTest {
    private HttpServer server;
    private ExecutorService executor;
    private String url;

    @BeforeEach void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor); server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterEach void stopServer() {
        server.stop(0); executor.shutdownNow();
    }

    @Test void acceptsOriginAndApiPrefixButRejectsAmbiguousOrCredentialUrls() {
        for (String suffix : List.of("", "/", "/api/v1", "/api/v1/")) {
            assertEquals(url + "/api/v1", new OnnCoreHttpClient(url + suffix).baseUrl());
        }
        for (String invalid : List.of("", "file:///tmp/onn", "ftp://onn.test", "http://user:secret@onn.test",
                "https://onn.test/other", "https://onn.test?token=secret", "https://onn.test#section")) {
            assertThrows(IllegalArgumentException.class, () -> new OnnCoreHttpClient(invalid).baseUrl());
        }
        assertThrows(IllegalArgumentException.class, () -> new OnnCoreHttpClient(null).baseUrl());
    }

    @Test void sendsOnlyExpectedReadRoutesAndEncodesClassAndColonId() throws Exception {
        List<String> calls = new ArrayList<>();
        server.createContext("/api/v1", exchange -> {
            synchronized (calls) { calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().toASCIIString()); }
            assertEquals("application/json", exchange.getRequestHeaders().getFirst("Accept"));
            if (exchange.getRequestMethod().equals("POST")) {
                assertEquals("application/json", exchange.getRequestHeaders().getFirst("Content-Type"));
                assertEquals("/demo/item", JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getString("match_class"));
            }
            respond(exchange, 200, "{\"ok\":true}");
        });
        OnnCoreHttpClient client = new OnnCoreHttpClient(url);
        client.schema(); client.sample("/demo/item"); client.instance("/demo/item", "id:a+b /中文");
        client.query(JSONObject.of("match_class", "/demo/item"));
        assertEquals(List.of("GET /api/v1/schema/all", "GET /api/v1/data/sample?class=%2Fdemo%2Fitem&limit=1",
                "GET /api/v1/data/instance?class_path=%2Fdemo%2Fitem&id=id%3Aa%2Bb+%2F%E4%B8%AD%E6%96%87",
                "POST /api/v1/query/execute"), calls);
    }

    @Test void sendsTrustedGatewayHeadersOnlyWhenConfigured() throws Exception {
        server.createContext("/api/v1/schema/all", exchange -> {
            assertEquals("Bearer test-token", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("test-token", exchange.getRequestHeaders().getFirst("X-OMS-Gateway-Token"));
            respond(exchange, 200, "{}");
        });
        new OnnCoreHttpClient(url, "test-token").schema();
    }

    @Test void onlyExactInstance404MeansAbsent() throws Exception {
        server.createContext("/api/v1", exchange -> respond(exchange, 404, "not found"));
        OnnCoreHttpClient client = new OnnCoreHttpClient(url);
        assertNull(client.instance("/demo/item", "absent"));
        OnnCoreHttpClient.OnnHttpException failure = assertThrows(OnnCoreHttpClient.OnnHttpException.class, client::schema);
        assertEquals(404, failure.status);
    }

    @Test void refusesRedirectsRatherThanFollowingAnotherEndpoint() {
        List<String> followed = new ArrayList<>();
        server.createContext("/api/v1/schema/all", exchange -> {
            exchange.getResponseHeaders().add("Location", url + "/redirected"); respond(exchange, 302, "{}");
        });
        server.createContext("/redirected", exchange -> { followed.add("redirect"); respond(exchange, 200, "{}"); });
        assertThrows(OnnCoreHttpClient.OnnHttpException.class, () -> new OnnCoreHttpClient(url).schema());
        assertTrue(followed.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "null", "[]", "{invalid"})
    void refusesMalformedOrNonObjectResponses(String response) {
        server.createContext("/api/v1/schema/all", exchange -> respond(exchange, 200, response));
        assertThrows(IllegalStateException.class, () -> new OnnCoreHttpClient(url).schema());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"degraded\":true}", "{\"success\":false}", "{\"error\":null}",
            "{\"partial\":true}", "{\"truncated\":true}", "{\"complete\":false}",
            "{\"status\":\"FAILED\"}", "{\"status\":\"timeout\"}", "{\"status\":\"cancelled\"}"})
    void refusesExplicitFailureOrIncompleteResponses(String response) {
        server.createContext("/api/v1/schema/all", exchange -> respond(exchange, 200, response));
        assertThrows(IllegalStateException.class, () -> new OnnCoreHttpClient(url).schema());
    }

    @Test void refusesQueryHasMoreButAllowsIntentionallyBoundedSample() throws Exception {
        server.createContext("/api/v1", exchange -> respond(exchange, 200, "{\"has_more\":true}"));
        OnnCoreHttpClient client = new OnnCoreHttpClient(url);
        assertThrows(IllegalStateException.class, () -> client.query(JSONObject.of("match_class", "/demo/item")));
        assertTrue(client.sample("/demo/item").getBooleanValue("has_more"));
    }

    @Test void preservesStructuredCodesWithoutLeakingServerBodiesOrCredentials() throws Exception {
        server.createContext("/api/v1/query/execute", exchange -> {
            assertEquals("Bearer gateway-secret", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("gateway-secret", exchange.getRequestHeaders().getFirst("X-OMS-Gateway-Token"));
            respond(exchange, 409, "{\"code\":\"SNAPSHOT_STALE\",\"error\":\"private-cursor-and-data\"}");
        });
        var failure = assertThrows(OnnCoreHttpClient.OnnHttpException.class,
            () -> new OnnCoreHttpClient(url, "gateway-secret").queryPage(new JSONObject(), Duration.ofSeconds(2)));
        assertEquals(409, failure.status);
        assertEquals("SNAPSHOT_STALE", failure.code);
        assertTrue(failure.getMessage().contains("SNAPSHOT_STALE"));
        assertFalse(failure.getMessage().contains("private-cursor"));
        assertFalse(failure.getMessage().contains("gateway-secret"));
    }

    @Test void pageEndpointsAllowContinuationsAndSnapshotDeleteAcceptsAnEmpty204() throws Exception {
        List<String> calls = new ArrayList<>();
        server.createContext("/api/v1/", exchange -> {
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            if (exchange.getRequestMethod().equals("DELETE")) {
                exchange.sendResponseHeaders(204, -1); exchange.close();
            } else respond(exchange, 200, "{\"has_more\":true}");
        });
        OnnCoreHttpClient client = new OnnCoreHttpClient(url);
        assertTrue(client.queryPage(new JSONObject(), Duration.ofSeconds(2)).getBooleanValue("has_more"));
        assertTrue(client.pipelinePage(new JSONObject(), Duration.ofSeconds(2)).getBooleanValue("has_more"));
        client.closeSnapshot("snapshot-id", Duration.ofSeconds(2));
        assertEquals(List.of("POST /api/v1/query/execute", "POST /api/v1/query/pipeline", "DELETE /api/v1/query/snapshots/snapshot-id"), calls);
    }

    @Test void refusesOversizedChunkedBodyWithoutAcceptingTruncatedJson() {
        server.createContext("/api/v1/schema/all", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                body.write(("{\"description\":\"" + "x".repeat(1024) + "\"}").getBytes(StandardCharsets.UTF_8));
            }
        });
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> new OnnCoreHttpClient(url, Duration.ofSeconds(2), 128).schema());
        assertTrue(failure.getMessage().contains("read limit"));
    }

    @Test void responseDeadlineIncludesAStalledBodyAfterSuccessfulHeaders() throws Exception {
        CountDownLatch releaseBody = new CountDownLatch(1);
        server.createContext("/api/v1/schema/all", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                body.write("{\"description\":\"".getBytes(StandardCharsets.UTF_8)); body.flush();
                try { releaseBody.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                body.write("complete\"}".getBytes(StandardCharsets.UTF_8));
            } catch (IOException expectedAfterCancellation) { }
        });
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(HttpTimeoutException.class,
                () -> new OnnCoreHttpClient(url, Duration.ofMillis(250), 1024).schema()));
        } finally { releaseBody.countDown(); }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
