package io.ontomato.dataengine.dataAdapter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OnnCoreSnapshotReaderTest {
    private static final String CLASS = "/test/item";
    private record Reply(int status, JSONObject body) { }

    static JSONObject page(JSONArray rows, long total, String cursor, String snapshot, long version) {
        return JSONObject.of("data", rows, "pagination", JSONObject.of("result_kind", "data", "consistency", "materialized",
            "total", total, "total_exact", true, "page_count", rows.size(), "has_more", cursor != null,
            "next_cursor", cursor, "snapshot_id", snapshot, "data_version", "epoch:" + version));
    }
    private static JSONObject row(String id) { return JSONObject.of("id", id, "classPath", CLASS, "attributes", JSONObject.of("name", id)); }
    private static Reply ok(String id, long total, String cursor, String snapshot) {
        return new Reply(200, page(JSONArray.of(row(id)), total, cursor, snapshot, 17));
    }

    @Test void readsMoreThanLegacyBoundWithoutLoggingOrMutatingCursorsAndClosesSession() throws Exception {
        List<Reply> replies = new ArrayList<>();
        for (int start = 0; start < 6001; start += 500) {
            JSONArray rows = new JSONArray();
            for (int i = start; i < Math.min(start + 500, 6001); i++) rows.add(row("id-" + i));
            replies.add(new Reply(200, page(rows, 6001, start + 500 < 6001 ? "opaque-" + start : null, "snapshot-a", 17)));
        }
        try (Fixture fixture = new Fixture(replies)) {
            JSONObject original = JSONObject.of("match_class", CLASS);
            var result = fixture.reader().read(original, 500);
            assertEquals(6001, result.rows().size());
            assertEquals("id-6000", result.rows().getLast().getString("id"));
            assertEquals(13, result.pages());
            assertTrue(result.snapshotReleased());
            assertEquals(List.of("snapshot-a"), fixture.closed);
            assertEquals(JSONObject.of("match_class", CLASS), original);
            for (JSONObject request : fixture.requests) {
                assertEquals(CLASS, request.getString("match_class"));
                assertEquals(500, request.getJSONObject("pagination").getIntValue("page_size"));
                assertEquals("snapshot", request.getJSONObject("pagination").getString("mode"));
            }
        }
    }

    @Test void staleExpiredAndAuthorizationVersionChangesDiscardAllEarlierRowsBeforeRestart() throws Exception {
        for (String code : List.of("SNAPSHOT_STALE", "SNAPSHOT_AUTH_CHANGED", "CURSOR_EXPIRED")) {
            try (Fixture fixture = new Fixture(List.of(ok("old", 2, "old-cursor", "old-snapshot"),
                new Reply(code.equals("CURSOR_EXPIRED") ? 410 : 409, JSONObject.of("code", code)),
                ok("new-1", 2, "new-cursor", "new-snapshot"), ok("new-2", 2, null, "new-snapshot")))) {
                var result = fixture.reader().read(JSONObject.of("match_class", CLASS), 1);
                assertEquals(List.of("new-1", "new-2"), result.rows().stream().map(r -> r.getString("id")).toList());
                assertEquals(2, result.attempts());
                assertFalse(fixture.requests.get(2).getJSONObject("pagination").containsKey("after"));
                assertEquals(List.of("old-snapshot", "new-snapshot"), fixture.closed);
            }
        }
    }

    @Test void doesNotRetryForbiddenGenericRateLimitsOrUnsupportedQueries() throws Exception {
        for (Reply reply : List.of(new Reply(403, JSONObject.of("code", "FIELD_FORBIDDEN")),
            new Reply(429, JSONObject.of("code", "OTHER_RATE_LIMIT")),
            new Reply(422, JSONObject.of("code", "CONSISTENCY_UNSUPPORTED")),
            new Reply(400, JSONObject.of("code", "CURSOR_SCOPE_MISMATCH")),
            new Reply(409, JSONObject.of("code", "OTHER_CONFLICT")))) {
            try (Fixture fixture = new Fixture(List.of(reply))) {
                var error = assertThrows(OnnCoreHttpClient.OnnHttpException.class,
                    () -> fixture.reader().read(JSONObject.of("match_class", CLASS), 1));
                assertEquals(reply.body.getString("code"), error.code);
                assertEquals(1, fixture.requests.size());
            }
        }
    }

    @Test void capacityAdmissionBackoffRecoversWithoutMixingRowsOrChangingTheQuery() throws Exception {
        Reply capacity = new Reply(429, JSONObject.of("code", "SNAPSHOT_CAPACITY_EXCEEDED"));
        try (Fixture fixture = new Fixture(List.of(ok("old", 2, "old-cursor", "old-snapshot"),
                capacity, capacity, capacity, capacity, ok("new", 1, null, "new-snapshot")))) {
            var result = fixture.reader().read(JSONObject.of("match_class", CLASS), 1);
            assertEquals(List.of("new"), result.rows().stream().map(r -> r.getString("id")).toList());
            assertEquals(5, result.attempts());
            assertEquals(List.of("old-snapshot", "new-snapshot"), fixture.closed);
            assertTrue(fixture.requests.subList(2, 6).stream().allMatch(q ->
                !q.getJSONObject("pagination").containsKey("after")
                && "snapshot".equals(q.getJSONObject("pagination").getString("mode"))));
        }
        try (Fixture fixture = new Fixture(List.of(capacity,
                new Reply(200, bindingPage(JSONArray.of(OnnCoreGraphCompilerTest.binding("a", "b")), 1, null, "bindings"))))) {
            var result = fixture.reader().readBindings(JSONObject.of("steps", new JSONArray()), 1,
                java.util.Map.of("a", OnnCoreGraphCompilerTest.A, "b", OnnCoreGraphCompilerTest.B));
            assertEquals(1, result.rows().size()); assertEquals(2, result.attempts());
            assertTrue(result.snapshotReleased());
        }
    }

    @Test void capacityBackoffIsBoundedByBothAdmissionCountAndWholeQueryDeadline() throws Exception {
        Reply capacity = new Reply(429, JSONObject.of("code", "SNAPSHOT_CAPACITY_EXCEEDED"));
        try (Fixture fixture = new Fixture(List.of(capacity))) {
            var error = assertThrows(OnnCoreHttpClient.OnnHttpException.class,
                () -> fixture.reader().read(JSONObject.of("match_class", CLASS), 1));
            assertEquals(429, error.status); assertEquals(9, fixture.requests.size());
        }
        try (Fixture fixture = new Fixture(List.of(capacity))) {
            assertThrows(java.net.http.HttpTimeoutException.class,
                () -> new OnnCoreSnapshotReader(fixture.client(), 10, 10000, Duration.ofMillis(50), 3)
                    .read(JSONObject.of("match_class", CLASS), 1));
            assertEquals(1, fixture.requests.size());
        }
    }

    @Test void busyRetriesAreBoundedAndDoNotDowngradeConsistency() throws Exception {
        Reply busy = new Reply(409, JSONObject.of("code", "SNAPSHOT_BUSY"));
        try (Fixture fixture = new Fixture(List.of(busy, busy, busy))) {
            assertThrows(OnnCoreHttpClient.OnnHttpException.class, () -> fixture.reader().read(JSONObject.of("match_class", CLASS), 1));
            assertEquals(3, fixture.requests.size());
            assertTrue(fixture.requests.stream().noneMatch(q -> q.getJSONObject("pagination").containsKey("after")));
        }
    }

    @Test void refusesBrokenCompletenessAndCrossPageProofsAndAlwaysAttemptsCleanup() throws Exception {
        for (String change : List.of("version", "total", "snapshot", "duplicate", "count", "exact", "consistency", "truncated", "repeat_cursor", "missing_rows", "class")) {
            JSONObject last = page(JSONArray.of(row("b")), 2, null, "snap", 17);
            JSONObject meta = last.getJSONObject("pagination");
            switch (change) {
                case "version" -> meta.put("data_version", "epoch:18");
                case "total" -> meta.put("total", 3);
                case "snapshot" -> meta.put("snapshot_id", "different");
                case "duplicate" -> last.put("data", JSONArray.of(row("a")));
                case "count" -> meta.put("page_count", 1.5);
                case "exact" -> meta.put("total_exact", "true");
                case "consistency" -> meta.put("consistency", "version_checked");
                case "truncated" -> last.put("truncated", true);
                case "repeat_cursor" -> { meta.put("has_more", true); meta.put("next_cursor", "cursor"); }
                case "missing_rows" -> { last.put("data", new JSONArray()); meta.put("page_count", 0); }
                case "class" -> last.getJSONArray("data").getJSONObject(0).put("classPath", "/other/item");
            }
            try (Fixture fixture = new Fixture(List.of(ok("a", 2, "cursor", "snap"), new Reply(200, last)))) {
                assertThrows(IllegalStateException.class, () -> fixture.reader().read(JSONObject.of("match_class", CLASS), 1), change);
                assertEquals(List.of("snap"), fixture.closed, change);
            }
        }
    }

    @Test void enforcesRowsBytesAndWholeQueryDeadline() throws Exception {
        try (Fixture fixture = new Fixture(List.of(ok("a", 2, "cursor", "snap")))) {
            assertThrows(IllegalStateException.class, () -> new OnnCoreSnapshotReader(fixture.client(), 1, 10000, Duration.ofSeconds(2), 3)
                .read(JSONObject.of("match_class", CLASS), 1));
            assertEquals(List.of("snap"), fixture.closed);
        }
        try (Fixture fixture = new Fixture(List.of(ok("a", 1, null, "snap")))) {
            assertThrows(IllegalStateException.class, () -> new OnnCoreSnapshotReader(fixture.client(), 10, 10, Duration.ofSeconds(2), 3)
                .read(JSONObject.of("match_class", CLASS), 1));
            assertEquals(List.of("snap"), fixture.closed);
        }
        try (Fixture fixture = new Fixture(List.of(new Reply(409, JSONObject.of("code", "SNAPSHOT_BUSY"))))) {
            assertThrows(java.net.http.HttpTimeoutException.class, () -> new OnnCoreSnapshotReader(fixture.client(), 10, 10000, Duration.ofMillis(50), 3)
                .read(JSONObject.of("match_class", CLASS), 1));
            assertEquals(1, fixture.requests.size());
        }
    }

    @Test void acceptsAnExactEmptySetWithoutCreatingOrClosingASession() throws Exception {
        try (Fixture fixture = new Fixture(List.of(new Reply(200, page(new JSONArray(), 0, null, null, 17))))) {
            var result = fixture.reader().read(JSONObject.of("match_class", CLASS), 10);
            assertTrue(result.rows().isEmpty());
            assertTrue(result.snapshotReleased());
            assertTrue(fixture.closed.isEmpty());
        }
    }

    @Test void countOnlyClosesTheMaterializedSnapshotAfterItsFirstCertifiedPage() throws Exception {
        try (Fixture fixture = new Fixture(List.of(ok("a", 6001, "private-cursor", "snap")))) {
            var result = fixture.reader().read(JSONObject.of("match_class", CLASS), 1, true);
            assertEquals(6001, result.total());
            assertEquals(1, result.rows().size());
            assertEquals(1, fixture.requests.size());
            assertTrue(result.snapshotReleased());
            assertEquals(List.of("snap"), fixture.closed);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger next = new AtomicInteger();
        final List<JSONObject> requests = new CopyOnWriteArrayList<>();
        final List<String> closed = new CopyOnWriteArrayList<>();
        final List<String> routes = new CopyOnWriteArrayList<>();
        final List<Reply> replies;
        Fixture(List<Reply> replies) throws IOException {
            this.replies = replies;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/", this::respond);
            server.start();
        }
        OnnCoreHttpClient client() { return new OnnCoreHttpClient("http://127.0.0.1:" + server.getAddress().getPort()); }
        OnnCoreSnapshotReader reader() { return new OnnCoreSnapshotReader(client()); }
        void respond(HttpExchange exchange) throws IOException {
            routes.add(exchange.getRequestURI().getPath());
            if (exchange.getRequestMethod().equals("DELETE")) {
                closed.add(exchange.getRequestURI().getPath().substring("/api/v1/query/snapshots/".length()));
                exchange.sendResponseHeaders(204, -1); exchange.close(); return;
            }
            requests.add(JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
            int index = next.getAndIncrement();
            Reply reply = replies.get(Math.min(index, replies.size() - 1));
            byte[] bytes = JSON.toJSONBytes(reply.body);
            exchange.sendResponseHeaders(reply.status, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
            exchange.close();
        }
        public void close() { server.stop(0); }
    }

    private static JSONObject bindingPage(JSONArray rows, long total, String cursor, String snapshot) {
        JSONObject page = page(rows, total, cursor, snapshot, 17);
        page.put("bindings", page.remove("data")); page.getJSONObject("pagination").put("result_kind", "bindings");
        return page;
    }

    @Test void bindingPaginationPreservesMultiplicityAndColonIdentitiesAndUsesPipelineRoute() throws Exception {
        var bindings = new java.util.LinkedHashMap<String, String>();
        bindings.put("a", OnnCoreGraphCompilerTest.A); bindings.put("b", OnnCoreGraphCompilerTest.B);
        try (Fixture fixture = new Fixture(List.of(
            new Reply(200, bindingPage(JSONArray.of(OnnCoreGraphCompilerTest.binding("same:1", "same:1")), 2, "cursor", "snap")),
            new Reply(200, bindingPage(JSONArray.of(OnnCoreGraphCompilerTest.binding("same:1", "other:1")), 2, null, "snap"))))) {
            var result = fixture.reader().readBindings(JSONObject.of("steps", new JSONArray()), 1, bindings);
            assertEquals(2, result.rows().size()); assertEquals(2, result.pages()); assertTrue(result.snapshotReleased());
            assertEquals(List.of("/api/v1/query/pipeline", "/api/v1/query/pipeline", "/api/v1/query/snapshots/snap"), fixture.routes);
            assertTrue(fixture.requests.stream().allMatch(q -> "bindings".equals(q.getJSONObject("pagination").getString("result_kind"))));
        }
    }

    @Test void bindingPaginationRejectsMissingVariablesDuplicatesAndIncompletePagesAndReleasesSnapshot() throws Exception {
        var bindings = java.util.Map.of("a", OnnCoreGraphCompilerTest.A, "b", OnnCoreGraphCompilerTest.B);
        for (String fault : List.of("duplicate", "missing", "class", "null", "attributes", "total", "version", "exact", "snapshot", "kind", "count", "terminal")) {
            JSONObject row = OnnCoreGraphCompilerTest.binding("source", "second");
            JSONObject last = bindingPage(JSONArray.of(row), 2, null, "snap");
            JSONObject meta = last.getJSONObject("pagination");
            switch (fault) {
                case "duplicate" -> row.getJSONObject("b").put("id", "first");
                case "missing" -> row.remove("b");
                case "class" -> row.getJSONObject("b").put("class_path", "wrong");
                case "null" -> row.put("b", null);
                case "attributes" -> row.getJSONObject("b").remove("attributes");
                case "total" -> meta.put("total", 3);
                case "version" -> meta.put("data_version", "epoch:18");
                case "exact" -> meta.put("total_exact", false);
                case "snapshot" -> meta.put("snapshot_id", "other");
                case "kind" -> meta.put("result_kind", "data");
                case "count" -> meta.put("page_count", 2);
                case "terminal" -> meta.put("next_cursor", "unexpected");
            }
            try (Fixture fixture = new Fixture(List.of(
                new Reply(200, bindingPage(JSONArray.of(OnnCoreGraphCompilerTest.binding("source", "first")), 2, "cursor", "snap")), new Reply(200, last)))) {
                assertThrows(IllegalStateException.class, () -> fixture.reader().readBindings(JSONObject.of("steps", new JSONArray()), 1, bindings), fault);
                assertEquals(List.of("snap"), fixture.closed, fault);
            }
        }
    }
}
