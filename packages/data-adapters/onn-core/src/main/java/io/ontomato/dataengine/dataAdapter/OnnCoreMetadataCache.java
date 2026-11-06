package io.ontomato.dataengine.dataAdapter;

import java.util.LinkedHashMap;
import java.util.List;

/** Bounded metadata only: query results and authorization decisions are never cached. */
final class OnnCoreMetadataCache {
    private static final int MAX_SCOPES = 64;
    private static final long TTL = java.time.Duration.ofSeconds(5).toNanos();
    private static final LinkedHashMap<String, Entry> ENTRIES = new LinkedHashMap<>(16, .75f, true);
    private static final class Entry {
        OnnCoreSchema schema;
        String etag;
        OnnCoreCapabilities capabilities;
        long discovered;
    }
    private static synchronized Entry entry(OnnCoreHttpClient client) {
        String key = client.cacheScope();
        Entry entry = ENTRIES.computeIfAbsent(key, ignored -> new Entry());
        while (ENTRIES.size() > MAX_SCOPES) ENTRIES.remove(ENTRIES.keySet().iterator().next());
        return entry;
    }
    static synchronized void invalidate(OnnCoreHttpClient client) { ENTRIES.remove(client.cacheScope()); }

    static OnnCoreSchema schema(OnnCoreHttpClient client, List<String> log) throws Exception {
        Entry entry = entry(client);
        synchronized (entry) {
            if (entry.schema == null || entry.etag == null) refresh(client, entry, log);
            else log.add("CACHE schema (revalidated before publishing the answer)");
            return entry.schema;
        }
    }
    static OnnCoreCapabilities capabilities(OnnCoreHttpClient client, List<String> log) throws Exception {
        Entry entry = entry(client);
        synchronized (entry) {
            if (entry.capabilities == null || System.nanoTime() - entry.discovered > TTL) {
                log.add("GET /api/v1/system/capabilities");
                OnnCoreCapabilities fresh = OnnCoreCapabilities.discover(client);
                if (entry.capabilities != null && !entry.capabilities.buildIdentity().equals(fresh.buildIdentity())) {
                    entry.schema = null; entry.etag = null;
                    throw new IllegalStateException("ONN build changed; retry with refreshed metadata");
                }
                entry.capabilities = fresh;
                entry.discovered = System.nanoTime();
            } else log.add("CACHE capabilities (5s maximum age)");
            return entry.capabilities;
        }
    }
    static void validate(OnnCoreHttpClient client, OnnCoreSchema expected, List<String> log) throws Exception {
        Entry entry = entry(client);
        synchronized (entry) {
            refresh(client, entry, log);
            if (!expected.fingerprint.equals(entry.schema.fingerprint)) {
                invalidate(client);
                throw new IllegalStateException("ONN schema changed during query; re-import schema before retrying");
            }
        }
    }
    private static void refresh(OnnCoreHttpClient client, Entry entry, List<String> log) throws Exception {
        log.add("GET /api/v1/schema/all" + (entry.etag == null ? "" : " (If-None-Match)"));
        var response = client.schema(entry.etag);
        if (!response.notModified()) entry.schema = new OnnCoreSchema(response.body());
        if (entry.schema == null) throw new IllegalStateException("ONN schema cache has no body for 304");
        entry.etag = response.etag();
    }
}
