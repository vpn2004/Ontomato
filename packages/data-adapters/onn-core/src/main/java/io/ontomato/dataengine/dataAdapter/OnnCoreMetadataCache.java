package io.ontomato.dataengine.dataAdapter;

import java.util.LinkedHashMap;
import java.util.List;

/** Bounded metadata only: query results and authorization decisions are never cached. */
final class OnnCoreMetadataCache {
    private static final int MAX_SCOPES = 64;
    private static final long TTL = java.time.Duration.ofSeconds(5).toNanos();
    private static final LinkedHashMap<String, Entry> ENTRIES = new LinkedHashMap<>(16, .75f, true);
    private record SchemaState(OnnCoreSchema schema, String etag) { }
    private static final class Entry {
        SchemaState state;
        OnnCoreCapabilities capabilities;
        long discovered, generation;
    }
    private record Read(String scope, Entry entry, long generation, SchemaState state,
            OnnCoreCapabilities capabilities, long discovered) { }

    // 锁仅保护快照与发布，所有HTTP和JSON处理都在锁外执行。
    private static Read read(OnnCoreHttpClient client) throws InterruptedException {
        interrupted();
        String scope = client.cacheScope();
        long started = System.nanoTime();
        synchronized (OnnCoreMetadataCache.class) {
            if (client.timing() != null) client.timing().add("metadataLockWait", started);
            Entry entry = ENTRIES.computeIfAbsent(scope, ignored -> new Entry());
            while (ENTRIES.size() > MAX_SCOPES) ENTRIES.remove(ENTRIES.keySet().iterator().next());
            return new Read(scope, entry, entry.generation, entry.state, entry.capabilities, entry.discovered);
        }
    }
    static synchronized void invalidate(OnnCoreHttpClient client) { ENTRIES.remove(client.cacheScope()); }

    private static void current(Read read) {
        if (ENTRIES.get(read.scope) != read.entry || read.entry.generation != read.generation)
            throw new IllegalStateException("ONN metadata changed during request; retry with refreshed metadata");
    }
    private static void interrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("ONN metadata request was cancelled");
    }

    static OnnCoreSchema schema(OnnCoreHttpClient client, List<String> log) throws Exception {
        Read read = read(client);
        if (read.state == null || read.state.etag == null) return refresh(client, read, log).schema;
        log.add("CACHE schema (revalidated before publishing the answer)");
        return read.state.schema;
    }
    static OnnCoreCapabilities capabilities(OnnCoreHttpClient client, List<String> log) throws Exception {
        Read read = read(client);
        if (read.capabilities != null && System.nanoTime() - read.discovered <= TTL) {
            log.add("CACHE capabilities (5s maximum age)");
            return read.capabilities;
        }
        log.add("GET /api/v1/system/capabilities");
        OnnCoreCapabilities fresh = OnnCoreCapabilities.discover(client);
        interrupted();
        synchronized (OnnCoreMetadataCache.class) {
            current(read);
            if (read.entry.capabilities != null && !read.entry.capabilities.buildIdentity().equals(fresh.buildIdentity())) {
                ENTRIES.remove(read.scope);
                throw new IllegalStateException("ONN build changed; retry with refreshed metadata");
            }
            read.entry.capabilities = fresh;
            read.entry.discovered = System.nanoTime();
        }
        return fresh;
    }
    static void validate(OnnCoreHttpClient client, OnnCoreSchema expected, List<String> log) throws Exception {
        // 每个调用者在读取业务结果后独立发起校验，不复用早于读取完成的in-flight刷新。
        Read read = read(client);
        SchemaState fresh = refresh(client, read, log);
        if (!expected.fingerprint.equals(fresh.schema.fingerprint)) {
            synchronized (OnnCoreMetadataCache.class) {
                if (ENTRIES.get(read.scope) == read.entry) ENTRIES.remove(read.scope);
            }
            throw new IllegalStateException("ONN schema changed during query; re-import schema before retrying");
        }
    }
    private static SchemaState refresh(OnnCoreHttpClient client, Read read, List<String> log) throws Exception {
        String etag = read.state == null ? null : read.state.etag;
        log.add("GET /api/v1/schema/all" + (etag == null ? "" : " (If-None-Match)"));
        if (client.timing() != null) client.timing().count("metadataRefreshes", 1);
        var response = client.schema(etag);
        OnnCoreSchema schema = response.notModified() ? (read.state == null ? null : read.state.schema) : new OnnCoreSchema(response.body());
        if (schema == null) throw new IllegalStateException("ONN schema cache has no body for 304");
        SchemaState fresh = new SchemaState(schema, response.etag());
        interrupted();
        synchronized (OnnCoreMetadataCache.class) {
            current(read);
            SchemaState previous = read.entry.state;
            if (read.state == null && previous != null && !previous.schema.fingerprint.equals(schema.fingerprint))
                throw new IllegalStateException("ONN concurrent schema refresh changed; retry with refreshed metadata");
            if (previous != null && !previous.schema.fingerprint.equals(schema.fingerprint)) read.entry.generation++;
            read.entry.state = fresh;
        }
        return fresh;
    }
}
