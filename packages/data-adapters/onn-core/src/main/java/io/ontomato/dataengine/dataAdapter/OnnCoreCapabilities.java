package io.ontomato.dataengine.dataAdapter;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.HashMap;
import java.util.Map;

/** Negotiates only explicitly enabled, understood contracts; it never grants write access. */
record OnnCoreCapabilities(String commit, boolean snapshotReads, int pageSize) {
    static OnnCoreCapabilities discover(OnnCoreHttpClient client) throws Exception {
        try { return parse(client.capabilities()); }
        catch (OnnCoreHttpClient.OnnHttpException error) {
            if (error.status == 404) return new OnnCoreCapabilities("unknown", false, 0);
            throw error;
        }
    }

    static OnnCoreCapabilities parse(JSONObject wire) {
        JSONArray features = wire.getJSONArray("features");
        if (features == null || wire.getString("api_contract_version") == null)
            throw new IllegalStateException("ONN capability manifest is incomplete");
        Map<String, JSONObject> byId = new HashMap<>();
        for (Object raw : features) {
            JSONObject feature = JSONObject.from(raw);
            String id = feature.getString("id");
            if (id == null || !(feature.get("enabled") instanceof Boolean) || byId.put(id, feature) != null)
                throw new IllegalStateException("ONN capability manifest has invalid or duplicate features");
        }
        String commit = wire.getString("commit");
        if (commit == null || !commit.matches("[a-zA-Z0-9._-]{1,80}")) commit = "unknown";
        JSONObject snapshot = byId.get("snapshot_pagination");
        boolean supported = "v1".equals(wire.getString("api_contract_version"))
            && enabled(byId.get("query_contract"), "v2") && enabled(snapshot, "v2");
        if (!supported) return new OnnCoreCapabilities(commit, false, 0);
        JSONObject pagination = snapshot.getJSONObject("pagination");
        if (pagination == null || !contains(pagination, "modes", "snapshot") || !contains(pagination, "result_kinds", "data")
            || !"single_process_single_data_directory".equals(pagination.getString("boundary")))
            throw new IllegalStateException("ONN snapshot capability has an unsupported contract");
        int max;
        try { max = new java.math.BigDecimal(pagination.get("max_page_size").toString()).intValueExact(); }
        catch (RuntimeException error) { throw new IllegalStateException("ONN snapshot page bound is invalid"); }
        if (max < 1) throw new IllegalStateException("ONN snapshot page bound is invalid");
        return new OnnCoreCapabilities(commit, true, Math.min(500, max));
    }

    private static boolean contains(JSONObject object, String key, String value) {
        JSONArray values = object.getJSONArray(key);
        return values != null && values.contains(value);
    }

    private static boolean enabled(JSONObject feature, String version) {
        return feature != null && Boolean.TRUE.equals(feature.get("enabled")) && version.equals(feature.getString("contract_version"));
    }
}
