package io.ontomato.dataengine.dataAdapter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Reads one complete materialized result or fails; attempts never share accumulated pages. */
final class OnnCoreSnapshotReader {
    private final OnnCoreHttpClient client;
    private final int maxRows;
    private final long maxBytes;
    private final Duration timeout;
    private final int attempts;

    OnnCoreSnapshotReader(OnnCoreHttpClient client) { this(client, 100_000, 32L * 1024 * 1024, Duration.ofSeconds(45), 3); }

    OnnCoreSnapshotReader(OnnCoreHttpClient client, int maxRows, long maxBytes, Duration timeout, int attempts) {
        if (maxRows < 1 || maxBytes < 1 || timeout.isNegative() || timeout.isZero() || attempts < 1)
            throw new IllegalArgumentException("Invalid ONN snapshot read budget");
        this.client = client; this.maxRows = maxRows; this.maxBytes = maxBytes; this.timeout = timeout; this.attempts = attempts;
    }

    record Result(List<JSONObject> rows, long total, String dataVersion, int pages, int attempts,
            boolean snapshotReleased, JSONObject metrics, List<JSONObject> profiles) { }

    Result read(JSONObject original, int pageSize) throws Exception {
        return read(original, pageSize, false);
    }

    Result read(JSONObject original, int pageSize, boolean totalOnly) throws Exception {
        return read(original, pageSize, totalOnly, null);
    }

    Result readBindings(JSONObject original, int pageSize, java.util.Map<String, String> bindings) throws Exception {
        if (bindings == null || bindings.isEmpty()) throw new IllegalArgumentException("Missing binding classes");
        return read(original, pageSize, false, bindings);
    }

    private Result read(JSONObject original, int pageSize, boolean totalOnly, java.util.Map<String, String> bindings) throws Exception {
        return read(original, pageSize, totalOnly, bindings, bindings == null ? "data" : "bindings", bindings != null, false, false);
    }

    Result readNative(JSONObject original, int pageSize, String kind, boolean pipeline,
            java.util.Map<String, String> bindings, boolean inline, boolean profile) throws Exception {
        if (!Set.of("data", "bindings", "metrics", "return").contains(kind)) throw new IllegalArgumentException("Invalid ONN result kind");
        return read(original, pageSize, false, bindings, kind, pipeline, inline, profile);
    }

    private Result read(JSONObject original, int pageSize, boolean totalOnly, java.util.Map<String, String> bindings,
            String kind, boolean pipeline, boolean inline, boolean profile) throws Exception {
        if (original.containsKey("pagination") || original.containsKey("limit") || original.containsKey("offset") || original.containsKey("after"))
            throw new IllegalArgumentException("ONN snapshot query must not contain a live window or cursor");
        if (pageSize < 1 || pageSize > 1000) throw new IllegalArgumentException("Invalid ONN snapshot page size");
        long deadline = System.nanoTime() + timeout.toNanos();
        int restarts = 0, capacityWaits = 0;
        for (int attempt = 1; ; attempt++) {
            try { return readAttempt(original, pageSize, deadline, attempt, totalOnly, bindings, kind, pipeline, inline, profile); }
            catch (OnnCoreHttpClient.OnnHttpException error) {
                long backoff;
                if (error.status == 429 && "SNAPSHOT_CAPACITY_EXCEEDED".equals(error.code)) {
                    // Manager requests can compete for the server's bounded snapshot builders.
                    // Admission retries share the original deadline and never change consistency.
                    if (capacityWaits >= 8) throw error;
                    backoff = Math.min(1000L, 100L << capacityWaits++);
                } else {
                    if (!restartable(error) || ++restarts >= attempts) throw error;
                    backoff = 100L * restarts;
                }
                long delay = Math.min(backoff, Math.max(1, remaining(deadline).toMillis()));
                try { Thread.sleep(delay); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
            }
        }
    }

    private Result readAttempt(JSONObject original, int pageSize, long deadline, int attempt, boolean totalOnly,
            java.util.Map<String, String> bindings, String kind, boolean pipeline, boolean inline, boolean profile) throws Exception {
        JSONObject query = JSON.parseObject(JSON.toJSONString(original, com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue));
        JSONObject pagination = JSONObject.of("mode", "snapshot", "result_kind", kind, "page_size", pageSize);
        if (inline) pagination.put("elide_single_page_session", true);
        query.put("pagination", pagination);
        List<JSONObject> rows = new ArrayList<>();
        Set<String> identities = new HashSet<>(), cursors = new HashSet<>();
        String snapshotId = null;
        String version = null;
        long total = -1, bytes = 0;
        int pages = 0;
        boolean complete = false;
        JSONObject metrics = null;
        List<JSONObject> profiles = new ArrayList<>();
        try {
            while (true) {
                JSONObject response = client.page(query, remaining(deadline), pipeline, profile);
                JSONObject meta = response.getJSONObject("pagination");
                String currentId = meta == null ? null : meta.getString("snapshot_id");
                if (currentId != null && !currentId.isBlank() && snapshotId == null) snapshotId = currentId;
                if (meta == null || !Boolean.TRUE.equals(meta.get("total_exact"))
                    || Boolean.TRUE.equals(response.get("truncated"))
                    || !"materialized".equals(meta.getString("consistency")) || !kind.equals(meta.getString("result_kind"))
                    || !(meta.get("has_more") instanceof Boolean) || meta.get("fallback_reason") != null)
                    throw incomplete("missing exact materialized pagination metadata");
                long currentTotal = count(meta.get("total"));
                Object rawVersion = meta.get("data_version");
                if (!(rawVersion instanceof String currentVersion) || currentVersion.isBlank() || currentVersion.length() > 256)
                    throw incomplete("data version is missing or invalid");
                if (pages == 0) { total = currentTotal; version = currentVersion; }
                else if (total != currentTotal || !version.equals(currentVersion) || !Objects.equals(snapshotId, currentId))
                    throw incomplete("snapshot identity, total or data version changed");
                if (!kind.equals("metrics") && total > maxRows) throw incomplete("row budget exceeded; narrow the query");
                JSONObject ret = kind.equals("return") ? response.getJSONObject("return") : null;
                if (kind.equals("return") && ret == null) throw incomplete("missing return envelope");
                JSONArray data = kind.equals("return") ? ret.getJSONArray("rows") : response.getJSONArray(kind.equals("metrics") ? "data" : kind);
                // Go emits a nil row slice as null for empty aggregate results.
                if (data == null && count(meta.get("page_count")) == 0
                    && (kind.equals("return") ? ret.containsKey("rows") : response.containsKey(kind.equals("metrics") ? "data" : kind)))
                    data = new JSONArray();
                if (data == null || data.size() > pageSize || count(meta.get("page_count")) != data.size())
                    throw incomplete("page count does not match data");
                boolean noSession = currentId == null || currentId.isBlank();
                if (noSession && (pages > 0 || meta.getBooleanValue("has_more")
                    || (!kind.equals("metrics") && total > 0 && (!inline || total != data.size()))))
                    throw incomplete("snapshot identity is missing");
                if (kind.equals("metrics")) {
                    metrics = response.getJSONObject("metrics");
                    if (metrics == null || !data.isEmpty() || !noSession || meta.getBooleanValue("has_more"))
                        throw incomplete("metrics response contains rows or a session");
                    for (Object raw : original.getJSONArray("metrics")) {
                        String name = JSONObject.from(raw).getString("name");
                        if (count(metrics.get(name)) != total) throw incomplete("count metric differs from exact total");
                    }
                } else if (kind.equals("return") && pages == 0) metrics = ret.getJSONObject("metrics");
                if (response.getJSONObject("query_profile") != null) profiles.add(response.getJSONObject("query_profile"));
                bytes += JSON.toJSONBytes(response).length;
                if (bytes > maxBytes) throw incomplete("byte budget exceeded; narrow the query");
                for (Object raw : data) {
                    JSONObject row = JSONObject.from(raw);
                    String identity;
                    if (kind.equals("return")) identity = row.toJSONString();
                    else if (bindings != null) identity = OnnCoreGraphCompiler.bindingIdentity(row, bindings);
                    else {
                        String id = row.getString("id"), classPath = row.getString("classPath");
                        if (id == null || classPath == null || !classPath.equals(original.getString("match_class")))
                            throw incomplete("missing or mismatched object identity");
                        identity = JSON.toJSONString(List.of(classPath, id));
                    }
                    if (!identities.add(identity)) throw incomplete("duplicate result identity");
                    rows.add(row);
                }
                pages++;
                if (rows.size() > total || rows.size() > maxRows) throw incomplete("rows exceed the exact total or budget");
                String cursor = meta.getString("next_cursor");
                if (!meta.getBooleanValue("has_more")) {
                    if (cursor != null && !cursor.isBlank() || !kind.equals("metrics") && rows.size() != total)
                        throw incomplete("terminal page has a cursor or omits rows");
                    complete = true;
                    break;
                }
                if (data.isEmpty() || rows.size() >= total || cursor == null || cursor.isBlank() || !cursors.add(cursor))
                    throw incomplete("continuation makes no progress or repeats a cursor");
                // The server has already materialized and certified the entire match set.
                // COUNT(id) needs its exact total, not the remaining object payloads.
                if (totalOnly) { complete = true; break; }
                pagination.put("after", cursor);
            }
        } finally {
            if (!complete && snapshotId != null) release(snapshotId);
        }
        boolean released = snapshotId == null || release(snapshotId);
        return new Result(List.copyOf(rows), total, version, pages, attempt, released, metrics, List.copyOf(profiles));
    }

    private boolean release(String snapshotId) {
        // Cleanup uses its own small deadline, even when the query budget was exhausted.
        try { client.closeSnapshot(snapshotId, Duration.ofSeconds(1)); return true; }
        catch (Exception ignored) { if (ignored instanceof InterruptedException) Thread.currentThread().interrupt(); return false; }
    }

    private static boolean restartable(OnnCoreHttpClient.OnnHttpException error) {
        return error.status == 409 && Set.of("SNAPSHOT_BUSY", "SNAPSHOT_STALE", "SNAPSHOT_AUTH_CHANGED").contains(error.code)
            || error.status == 410 && "CURSOR_EXPIRED".equals(error.code);
    }

    private static Duration remaining(long deadline) throws HttpTimeoutException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new HttpTimeoutException("ONN snapshot exceeded its total query deadline");
        return Duration.ofNanos(nanos);
    }

    private static long count(Object raw) {
        try {
            long value = new java.math.BigDecimal(raw.toString()).longValueExact();
            if (value >= 0) return value;
        } catch (RuntimeException ignored) { }
        throw incomplete("invalid exact count or data version");
    }

    private static IllegalStateException incomplete(String reason) {
        return new IllegalStateException("ONN snapshot is incomplete: " + reason + "; no answer was produced");
    }
}
