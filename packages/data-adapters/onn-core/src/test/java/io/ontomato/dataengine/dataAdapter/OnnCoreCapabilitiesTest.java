package io.ontomato.dataengine.dataAdapter;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OnnCoreCapabilitiesTest {
    static JSONObject manifest() {
        return JSONObject.of("commit", "fb81695c", "api_contract_version", "v1", "features", JSONArray.of(
            JSONObject.of("id", "query_contract", "enabled", true, "contract_version", "v2"),
            JSONObject.of("id", "snapshot_pagination", "enabled", true, "contract_version", "v2", "pagination", JSONObject.of(
                "modes", List.of("snapshot"), "result_kinds", List.of("data"), "max_page_size", 1000,
                "boundary", "single_process_single_data_directory"))));
    }

    @Test void negotiatesEnabledKnownContractsOnly() {
        OnnCoreCapabilities result = OnnCoreCapabilities.parse(manifest());
        assertTrue(result.snapshotReads());
        assertEquals("fb81695c", result.commit());
        assertEquals(500, result.pageSize());
        for (String change : List.of("disabled", "unknown_snapshot", "unknown_query", "unknown_api", "missing")) {
            JSONObject wire = manifest();
            switch (change) {
                case "disabled" -> wire.getJSONArray("features").getJSONObject(1).put("enabled", false);
                case "unknown_snapshot" -> wire.getJSONArray("features").getJSONObject(1).put("contract_version", "v3");
                case "unknown_query" -> wire.getJSONArray("features").getJSONObject(0).put("contract_version", "v3");
                case "unknown_api" -> wire.put("api_contract_version", "v2");
                case "missing" -> wire.getJSONArray("features").clear();
            }
            assertFalse(OnnCoreCapabilities.parse(wire).snapshotReads(), change);
        }
    }

    @Test void rejectsMalformedClaimsAndRespectsSmallerServerPageBound() {
        JSONObject wire = manifest();
        wire.getJSONArray("features").getJSONObject(1).getJSONObject("pagination").put("max_page_size", 20);
        assertEquals(20, OnnCoreCapabilities.parse(wire).pageSize());
        for (String change : List.of("duplicate", "string_boolean", "no_features", "no_pagination", "bad_page", "bad_boundary")) {
            JSONObject invalid = manifest();
            switch (change) {
                case "duplicate" -> invalid.getJSONArray("features").add(invalid.getJSONArray("features").get(0));
                case "string_boolean" -> invalid.getJSONArray("features").getJSONObject(0).put("enabled", "true");
                case "no_features" -> invalid.remove("features");
                case "no_pagination" -> invalid.getJSONArray("features").getJSONObject(1).remove("pagination");
                case "bad_page" -> invalid.getJSONArray("features").getJSONObject(1).getJSONObject("pagination").put("max_page_size", 1.5);
                case "bad_boundary" -> invalid.getJSONArray("features").getJSONObject(1).getJSONObject("pagination").put("boundary", "distributed");
            }
            assertThrows(IllegalStateException.class, () -> OnnCoreCapabilities.parse(invalid), change);
        }
    }

    @Test void bindingsRequireEveryKnownEnabledGraphContractAndSnapshotKind() {
        JSONObject wire = manifest();
        wire.getJSONArray("features").getJSONObject(1).getJSONObject("pagination").getJSONArray("result_kinds").add("bindings");
        for (String feature : List.of("pipeline", "graph_query", "graph_binding_aggregate"))
            wire.getJSONArray("features").add(JSONObject.of("id", feature, "enabled", true, "contract_version", "v1"));
        assertTrue(OnnCoreCapabilities.parse(wire).bindingReads());
        for (int index = 0; index < wire.getJSONArray("features").size(); index++) {
            JSONObject bad = JSONObject.parseObject(wire.toJSONString());
            bad.getJSONArray("features").getJSONObject(index).put("enabled", false);
            assertFalse(OnnCoreCapabilities.parse(bad).bindingReads());
        }
        wire.getJSONArray("features").getJSONObject(1).getJSONObject("pagination").getJSONArray("result_kinds").remove("bindings");
        assertFalse(OnnCoreCapabilities.parse(wire).bindingReads());
    }
}
