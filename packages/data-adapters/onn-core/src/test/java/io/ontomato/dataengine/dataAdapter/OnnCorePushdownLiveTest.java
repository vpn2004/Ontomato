package io.ontomato.dataengine.dataAdapter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter.Feature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Runs only against an explicitly provisioned, isolated acceptance dataset. */
@EnabledIfEnvironmentVariable(named = "ONTOMATO_ONN_PUSHDOWN_LIVE_TEST", matches = "true")
class OnnCorePushdownLiveTest {
    private static final String CLASS = "/adapter/item";

    @Test void nativeReadsAgreeWithCompleteLocalEvaluationAcrossTheSemanticMatrix() throws Exception {
        String url = System.getenv("ONTOMATO_ONN_LIVE_URL"), token = System.getenv("ONTOMATO_ONN_LIVE_TOKEN");
        assertNotNull(url);
        OnnCoreHttpClient client = new OnnCoreHttpClient(url, token);
        ConditionalObjectWrites.Principal principal = null;
        if (System.getenv("ONTOMATO_ONN_LIVE_SUBJECT") != null) {
            principal = new ConditionalObjectWrites.Principal(System.getenv("ONTOMATO_ONN_LIVE_SUBJECT"), List.of("admin"));
            client = client.asPrincipal(principal);
        }
        OnnCoreSchema schema = new OnnCoreSchema(client.schema());
        boolean t30 = OnnCoreCapabilities.discover(client).pushdown();
        var all = new OnnCoreSnapshotReader(client).read(JSONObject.of("match_class", CLASS), 500);
        assertEquals(6001, all.total());
        var rows = all.rows().stream().map(r -> schema.flatten(CLASS, r)).toList();
        DataAdapterConnection connection = new DataAdapterConnection(); connection.setUrl(url); connection.setToken(token);
        OnnCoreAdapter adapter = new OnnCoreAdapter(connection, schema.ontology(), principal);
        JSONObject text = predicate("name", "=", "Item 42");
        JSONObject date = predicate("when", "=", "2026-01-15T10:30:00Z");
        List<JSONObject> predicates = Arrays.asList(text,
            predicate("name", "in", List.of("Item 0", "Item 5000")), predicate("flag", "=", true),
            logic("or", text, predicate("name", "=", "Item 5000")),
            logic("and", predicate("category", "=", "A"), predicate("amount", ">", 5990)),
            logic("or", text, date), predicate("when", "is", null), date,
            predicate("amount", "between", List.of(5990, 6000)), predicate("name", "in", Arrays.asList("Item 42", null)),
            predicate("name", "!=", "1"), predicate("category", "=", null));
        int checked = 0;
        for (JSONObject predicate : predicates) {
            JSONObject dsl = query(List.of(field("id", "id"), field("name", "name")), predicate);
            JSONObject result = compare(adapter, schema, rows, dsl);
            boolean pushed = result.getJSONObject("evidence").getBooleanValue("filterPushdown");
            assertEquals(t30 || checked < 5, pushed, predicate.toString());
            if (checked == 0) assertEquals(1, result.getJSONObject("evidence").getIntValue("objectsRead"));
            checked++;
        }
        for (String literal : List.of("01", "2026-01-15T10:30:00Z")) {
            JSONObject result = compare(adapter, schema, rows, query(List.of(field("id", "id")), predicate("name", "=", literal)));
            assertEquals(1, result.getJSONArray("answer").size(), "Text equality must not adopt numeric or temporal coercion");
            assertTrue(result.getJSONObject("evidence").getBooleanValue("filterPushdown"));
            checked++;
        }
        JSONObject sum = field("amount", "sum"); sum.put("function", "sum");
        JSONObject grouped = query(List.of(field("category", "category"), sum), predicate("category", "=", "A"));
        output(grouped).put("group_by", JSONObject.of("fields", List.of(JSONObject.of("field", "category"))));
        compare(adapter, schema, rows, grouped); checked++;
        JSONObject count = field("id", "total"); count.put("function", "count");
        JSONObject countQuery = query(List.of(count), null);
        JSONObject nativeCount = compare(adapter, schema, rows, countQuery); checked++;
        assertEquals(t30 ? 0 : 1, nativeCount.getJSONObject("evidence").getIntValue("objectsRead"));
        assertTrue(nativeCount.getJSONObject("evidence").getBooleanValue("aggregatePushdown"));
        assertTrue(nativeCount.getJSONObject("evidence").getBooleanValue("snapshotReleased"));
        output(countQuery).put("limit", JSONObject.of("count", 1, "offset", 1));
        compare(adapter, schema, rows, countQuery); checked++;
        System.out.println("ONN_NATIVE_ACCEPTANCE cases=" + checked + " objects=" + rows.size()
            + " countObjectsDownloaded=" + (t30 ? 0 : 1) + " equalityObjectsDownloaded=1");
    }

    private JSONObject compare(OnnCoreAdapter adapter, OnnCoreSchema schema, List<JSONObject> rows, JSONObject dsl) throws Exception {
        JSONArray expected = new OnnCoreDslCompiler(dsl, schema, schema.ontology()).evaluate(rows);
        JSONObject result = JSON.parseArray(adapter.executeDsl(JSON.toJSONString(List.of(dsl), Feature.WriteMapNullValue),
            null, DslExecutionMode.OPTIMIZED, new ArrayList<>())).getJSONObject(0);
        assertEquals(JSON.parseArray(JSON.toJSONString(expected, Feature.WriteMapNullValue)), result.getJSONArray("answer"), dsl.toString());
        assertTrue(result.getJSONObject("evidence").getBooleanValue("complete"));
        return result;
    }

    private static JSONObject field(String name, String alias) { return JSONObject.of("variable", "o", "field", name, "as", alias); }
    private static JSONObject predicate(String field, String operator, Object value) { return JSONObject.of("field", field, "operator", operator, "value", value); }
    private static JSONObject logic(String op, JSONObject... children) { return JSONObject.of("operator", "logic", op, List.of(children)); }
    private static JSONObject output(JSONObject dsl) { return dsl.getJSONObject("answer").getJSONArray("steps").getJSONObject(0).getJSONObject("output"); }
    private static JSONObject query(List<JSONObject> fields, JSONObject predicate) {
        JSONObject object = JSONObject.of("class", CLASS, "variable", "o");
        if (predicate != null) object.put("conditions", JSONObject.of("properties", predicate));
        return JSONObject.of("answer", JSONObject.of("steps", List.of(JSONObject.of("graph", JSONObject.of("patterns", List.of(
            JSONObject.of("objects", List.of(object), "relationships", List.of()))), "output", JSONObject.of("fields", fields, "to_user", true)))));
    }
}
