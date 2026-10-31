package io.ontomato.dataengine.dataAdapter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OnnCoreConditionalWriterTest {
    static JSONObject ready() {
        JSONObject manifest = OnnCoreCapabilitiesTest.manifest(); manifest.put("authorization_mode", "scoped_policy");
        for (var entry : Map.of("conditional_write", "v2", "retry_idempotency", "v2", "audit_trail", "v1", "action_governance", "v1", "resource_governance", "v1").entrySet())
            manifest.getJSONArray("features").add(JSONObject.of("id", entry.getKey(), "enabled", true, "contract_version", entry.getValue()));
        return manifest;
    }
    static ConditionalObjectWrites.Principal principal() { return new ConditionalObjectWrites.Principal("test-user", List.of("test-writer")); }

    @Test void requiresExplicitIdentityAndAllKnownEnabledWriteContracts() {
        assertThrows(IllegalArgumentException.class, () -> new ConditionalObjectWrites.Principal("anonymous\r\nadmin", List.of("admin")));
        assertThrows(IllegalArgumentException.class, () -> new ConditionalObjectWrites.Principal("user", List.of()));
        assertThrows(UnsupportedOperationException.class, () -> new OnnCoreHttpClient("http://localhost").asPrincipal(principal()));
        OnnCoreConditionalWriter.requireReady(ready());
        for (int i = 2; i < ready().getJSONArray("features").size(); i++) {
            JSONObject disabled = ready(); disabled.getJSONArray("features").getJSONObject(i).put("enabled", false);
            assertThrows(UnsupportedOperationException.class, () -> OnnCoreConditionalWriter.requireReady(disabled));
        }
        JSONObject disabled = ready(); disabled.put("authorization_mode", "disabled");
        assertThrows(UnsupportedOperationException.class, () -> OnnCoreConditionalWriter.requireReady(disabled));
    }

    @Test void preservesExpectedVersionNullsIdentityAndIdempotencyWithoutRetryingConflicts() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<JSONObject> writes = new ArrayList<>(); List<String> matches = new ArrayList<>(); AtomicInteger gets = new AtomicInteger();
        server.createContext("/api/v1/", exchange -> {
            assertEquals("test-user", exchange.getRequestHeaders().getFirst("X-OMS-Subject"));
            assertEquals("test-writer", exchange.getRequestHeaders().getFirst("X-OMS-Roles"));
            assertEquals("test-token", exchange.getRequestHeaders().getFirst("X-OMS-Gateway-Token"));
            String path = exchange.getRequestURI().getPath(); JSONObject response; int status = 200;
            if (path.endsWith("capabilities")) response = ready();
            else if (path.endsWith("schema/all")) response = OnnCoreGraphCompilerTest.schemaWire();
            else if (exchange.getRequestMethod().equals("GET")) {
                gets.incrementAndGet();
                response = JSONObject.of("id", "id:1", "classPath", OnnCoreGraphCompilerTest.A, "attributes", JSONObject.of("name", "old"), "version", 1, "etag", "\"v1-hash\"");
            } else {
                matches.add(exchange.getRequestHeaders().getFirst("If-Match"));
                assertEquals("decision-1", exchange.getRequestHeaders().getFirst("X-OMS-Idempotency-Key"));
                writes.add(JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                assertEquals("PATCH", exchange.getRequestMethod());
                if (writes.size() == 1) response = JSONObject.of("status", "ok", "updated_attributes", List.of("name"), "version", 2, "etag", "\"v2-hash\"");
                else { status = 412; response = JSONObject.of("code", "PRECONDITION_FAILED", "detail", "private-body-secret"); }
            }
            byte[] bytes = JSON.toJSONBytes(response); exchange.sendResponseHeaders(status, bytes.length);
            try (var stream = exchange.getResponseBody()) { stream.write(bytes); } exchange.close();
        });
        server.start();
        try {
            OnnCoreSchema schema = new OnnCoreSchema(OnnCoreGraphCompilerTest.schemaWire());
            OnnCoreConditionalWriter writer = new OnnCoreConditionalWriter(new OnnCoreHttpClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-token"), schema.ontology(), principal());
            JSONObject read = writer.read(OnnCoreGraphCompilerTest.A, "id:1");
            JSONObject values = new JSONObject(); values.put("name", null);
            assertEquals(2, writer.patch(OnnCoreGraphCompilerTest.A, "id:1", values, read.getString("etag"), "decision-1").getIntValue("version"));
            var error = assertThrows(OnnCoreHttpClient.OnnHttpException.class, () -> writer.patch(OnnCoreGraphCompilerTest.A, "id:1", values, "\"v1-hash\"", "decision-1"));
            assertEquals(412, error.status); assertEquals("PRECONDITION_FAILED", error.code);
            assertFalse(error.toString().contains("private-body-secret")); assertFalse(error.toString().contains("test-token"));
            assertEquals(2, writes.size()); assertEquals(1, gets.get(), "Conflict must not trigger re-read and overwrite");
            assertEquals(List.of("\"v1-hash\"", "\"v1-hash\""), matches);
            assertEquals("id:1", writes.getFirst().getString("id"));
            assertEquals(OnnCoreGraphCompilerTest.A, writes.getFirst().getString("class_path"));
            assertTrue(writes.getFirst().getJSONObject("attributes").containsKey("name"));
            assertNull(writes.getFirst().getJSONObject("attributes").get("name"));
            for (String etag : Arrays.asList(null, "*", "W/\"v1-hash\"", "\"a\",\"b\""))
                assertThrows(IllegalArgumentException.class, () -> writer.patch(OnnCoreGraphCompilerTest.A, "id:1", values, etag, "decision-1"));
            assertEquals(2, writes.size());
        } finally { server.stop(0); }
    }
}
