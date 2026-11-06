package io.ontomato.dataengine.dataAdapter;

import com.alibaba.fastjson2.*;
import java.nio.file.*;
import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Reads only an explicitly provisioned isolated dataset; never imports or mutates it. */
@EnabledIfEnvironmentVariable(named = "ONTOMATO_ONN_T30_LIVE_TEST", matches = "true")
class OnnCoreT30LiveTest {
    @Test void qualityCasesAgreeWithOriginalOraclesAndCompleteResidualEvaluation() throws Exception {
        String url = System.getenv("ONTOMATO_ONN_LIVE_URL"), token = System.getenv("ONTOMATO_ONN_LIVE_TOKEN");
        assertNotNull(url); assertNotNull(token);
        var principal = new ConditionalObjectWrites.Principal("ontomato-t30-acceptance", List.of("admin"));
        OnnCoreHttpClient client = new OnnCoreHttpClient(url, token).asPrincipal(principal);
        OnnCoreSchema schema = new OnnCoreSchema(client.schema());
        assertTrue(OnnCoreCapabilities.discover(client).pushdown());
        DataAdapterConnection connection = new DataAdapterConnection(); connection.setUrl(url); connection.setToken(token);
        OnnCoreAdapter adapter = new OnnCoreAdapter(connection, schema.ontology(), principal);
        JSONArray cases = JSON.parseArray(Files.readString(Path.of(System.getenv("ONTOMATO_ONN_T30_CASES"))));
        JSONObject graphCounts = JSON.parseObject(cases.stream().map(JSONObject::from).filter(c -> "two_hop_full".equals(c.getString("id"))).findFirst().orElseThrow().getJSONObject("dsl").toJSONString());
        JSONObject graphOutput = graphCounts.getJSONObject("answer").getJSONArray("steps").getJSONObject(0).getJSONObject("output");
        JSONObject paths = JSONObject.of("variable", "c", "field", "id", "function", "count", "as", "paths");
        JSONObject objects = JSONObject.of("variable", "c", "field", "id", "function", "count", "distinct", true, "as", "objects");
        graphOutput.put("fields", JSONArray.of(paths, objects));
        cases.add(JSONObject.of("id", "extra_graph_paths_vs_objects", "dsl", graphCounts));
        JSONObject attributeCount = JSON.parseObject(cases.getJSONObject(0).getJSONObject("dsl").toJSONString());
        JSONObject scalarObject = attributeCount.getJSONObject("answer").getJSONArray("steps").getJSONObject(0).getJSONObject("graph").getJSONArray("patterns").getJSONObject(0).getJSONArray("objects").getJSONObject(0);
        scalarObject.put("class", "/quality_traceability_demo/batch");
        JSONObject scalarOutput = attributeCount.getJSONObject("answer").getJSONArray("steps").getJSONObject(0).getJSONObject("output");
        scalarOutput.put("fields", JSONArray.of(JSONObject.of("variable", "o", "field", "raw_material_code", "function", "count", "as", "nonNull")));
        cases.add(JSONObject.of("id", "extra_attribute_nonnull_count", "dsl", attributeCount));
        JSONObject boolGroups = JSON.parseObject(attributeCount.toJSONString());
        JSONObject boolOutput = boolGroups.getJSONObject("answer").getJSONArray("steps").getJSONObject(0).getJSONObject("output");
        boolOutput.put("fields", JSONArray.of(JSONObject.of("variable", "o", "field", "defect_flag", "as", "flag"), JSONObject.of("variable", "o", "field", "id", "function", "count", "as", "total")));
        boolOutput.put("group_by", JSONObject.of("fields", JSONArray.of(JSONObject.of("variable", "o", "field", "defect_flag"))));
        boolOutput.put("sort", JSONObject.of("fields", JSONArray.of(JSONObject.of("variable", "o", "field", "defect_flag", "order", "asc"))));
        cases.add(JSONObject.of("id", "extra_bool_group_count", "dsl", boolGroups));
        JSONArray executions = new JSONArray();
        for (Object raw : cases) {
            JSONObject test = JSONObject.from(raw), dsl = test.getJSONObject("dsl");
            JSONObject result = JSON.parseArray(adapter.executeDsl(JSON.toJSONString(List.of(dsl), JSONWriter.Feature.WriteMapNullValue),
                null, DslExecutionMode.OPTIMIZED, new ArrayList<>())).getJSONObject(0);
            JSONArray baseline;
            if (OnnCoreGraphCompiler.isGraph(dsl)) {
                var plan = new OnnCoreGraphCompiler(dsl, schema, schema.ontology());
                baseline = plan.evaluate(new OnnCoreSnapshotReader(client).readBindings(plan.nativePipeline(), 500, plan.bindings()).rows());
            } else {
                var plan = new OnnCoreDslCompiler(dsl, schema, schema.ontology());
                var all = new OnnCoreSnapshotReader(client).read(JSONObject.of("match_class", plan.className), 500);
                baseline = plan.evaluate(all.rows().stream().map(row -> schema.flatten(plan.className, row)).toList());
            }
            assertEquals(canonical(baseline, true), canonical(result.getJSONArray("answer"), true), test.getString("id") + " differs from exact residual");
            if (test.getJSONArray("expected") != null) assertOracle(test.getJSONArray("expected"), result.getJSONArray("answer"), test.getBooleanValue("ordered"), test.getString("id"));
            if (test.getString("id").startsWith("extra_")) assertTrue(result.getJSONObject("evidence").getBooleanValue("aggregatePushdown"), test.getString("id"));
            executions.add(JSONObject.of("case", test.getString("id"), "passed", true, "execution", result));
            System.out.println("T30_ADAPTER_PASS " + test.getString("id") + " " + result.getJSONObject("evidence").getString("resultKind"));
        }
        String output = System.getenv("ONTOMATO_ONN_T30_RESULTS");
        if (output != null) Files.writeString(Path.of(output), JSON.toJSONString(executions, JSONWriter.Feature.PrettyFormat, JSONWriter.Feature.WriteMapNullValue));
        assertTrue(cases.size() >= 20);
    }
    private static List<String> canonical(JSONArray rows, boolean ordered) {
        List<String> result = new ArrayList<>();
        for (Object raw : rows) {
            TreeMap<String, Object> row = new TreeMap<>();
            JSONObject.from(raw).forEach((key, value) -> row.put(key, value instanceof Number ? new BigDecimal(value.toString()).stripTrailingZeros() : value));
            result.add(JSON.toJSONString(row, JSONWriter.Feature.WriteMapNullValue));
        }
        if (!ordered) Collections.sort(result); return result;
    }
    private static void assertOracle(JSONArray expected, JSONArray actual, boolean ordered, String id) {
        assertEquals(expected.size(), actual.size(), id);
        if (!ordered) {
            expected = JSON.parseArray(JSON.toJSONString(expected, JSONWriter.Feature.WriteMapNullValue));
            actual = JSON.parseArray(JSON.toJSONString(actual, JSONWriter.Feature.WriteMapNullValue));
            Comparator<Object> comparator = Comparator.comparing(row -> JSON.toJSONString(new TreeMap<>(JSONObject.from(row)), JSONWriter.Feature.WriteMapNullValue));
            expected.sort(comparator); actual.sort(comparator);
        }
        for (int i = 0; i < expected.size(); i++) {
            JSONObject left = expected.getJSONObject(i), right = actual.getJSONObject(i);
            assertEquals(left.keySet(), right.keySet(), id);
            for (String key : left.keySet()) {
                Object a = left.get(key), b = right.get(key);
                if (a instanceof Number && b instanceof Number)
                    assertEquals(((Number) a).doubleValue(), ((Number) b).doubleValue(), Math.max(1e-7, Math.abs(((Number) a).doubleValue()) * 1e-8), id + "." + key);
                else assertEquals(a, b, id + "." + key);
            }
        }
    }
}
