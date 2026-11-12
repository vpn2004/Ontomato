package io.ontomato.dataengine.dataAdapter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter.Feature;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OnnCoreQueryPlanTest {
    private final OnnCoreSchema schema = new OnnCoreSchema(JSONObject.of("ontologies", JSONArray.of(JSONObject.of(
        "path", "/test/item", "attributes", JSONArray.of(JSONObject.of("name", "amount", "dataType", "float"),
            JSONObject.of("name", "when", "dataType", "datetime")))), "relations", new JSONArray()));

    static JSONObject field(String variable, String name, String alias) {
        return JSONObject.of("variable", variable, "field", name, "as", alias);
    }
    static JSONObject step(String cls, String variable, JSONArray fields, String save) {
        JSONObject output = JSONObject.of("to_user", save == null, "fields", fields);
        if (save != null) output.put("save_table", save);
        return JSONObject.of("graph", JSONObject.of("patterns", JSONArray.of(JSONObject.of("objects", JSONArray.of(
            JSONObject.of("class", cls, "variable", variable, "idx", 0))))), "output", output);
    }
    static JSONObject query() {
        return JSONObject.of("answer", JSONObject.of("steps", JSONArray.of(
            step("/test/item", "i", JSONArray.of(field("i", "id", "id"), field("i", "amount", "amount")), "/temp01"),
            step("/temp01", "t", JSONArray.of(field("t", "id", "id")), null))));
    }
    static JSONArray steps(JSONObject query) { return query.getJSONObject("answer").getJSONArray("steps"); }
    private OnnCoreQueryPlan compile(JSONObject q) { return new OnnCoreQueryPlan(q, schema, schema.ontology()); }

    @Test void compilesBeforeReadingAndDoesNotMutateInputOrLimitIntermediateTo100() {
        JSONObject q = query(), before = JSON.parseObject(q.toJSONString());
        OnnCoreQueryPlan plan = compile(q);
        assertEquals(before, q);
        JSONObject sourceOutput = steps(plan.source).getJSONObject(0).getJSONObject("output");
        assertTrue(sourceOutput.getBooleanValue("to_user")); assertFalse(sourceOutput.containsKey("save_table"));
        assertEquals(5000, sourceOutput.getJSONObject("limit").getIntValue("count"));
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 123; i++) rows.add(JSONObject.of("id", "real:" + i, "amount", i));
        JSONObject count = field("t", "id", "total"); count.put("function", "count");
        steps(q).getJSONObject(1).getJSONObject("output").put("fields", JSONArray.of(count));
        assertEquals(123L, compile(q).evaluate(rows).getJSONObject(0).getLongValue("total"));
    }

    @Test void temporaryIdentityAliasRemainsDataAndNumericPrecisionIsPreserved() {
        JSONObject q = query();
        JSONObject sum = field("t", "amount", "total"); sum.put("function", "sum");
        steps(q).getJSONObject(1).getJSONObject("output").put("fields", JSONArray.of(sum));
        JSONArray rows = JSONArray.of(JSONObject.of("id", "business:id", "amount", new BigDecimal("1000000000000000000.01")),
            JSONObject.of("id", "another:id", "amount", new BigDecimal("0.02")));
        assertEquals(0, new BigDecimal("1000000000000000000.03").compareTo((BigDecimal) compile(q).evaluate(rows).getJSONObject(0).get("total")));
        q = query();
        steps(q).getJSONObject(1).getJSONObject("graph").getJSONArray("patterns").getJSONObject(0)
            .getJSONArray("objects").getJSONObject(0).put("conditions", JSONObject.of("properties",
                JSONObject.of("field", "id", "operator", "=", "value", "business:id")));
        assertEquals("business:id", compile(q).evaluate(rows).getJSONObject(0).getString("id"));
    }

    @Test void chainsDistinctThenCountWithoutDroppingNullsOrConflatingTypes() {
        JSONObject q = query();
        JSONObject distinct = field("t", "amount", "amount"); distinct.put("distinct", true);
        steps(q).set(1, step("/temp01", "t", JSONArray.of(distinct), "/temp02"));
        JSONObject count = field("u", "amount", "count"); count.put("function", "count");
        steps(q).add(step("/temp02", "u", JSONArray.of(count), null));
        JSONArray rows = JSONArray.of(JSONObject.of("amount", 1), JSONObject.of("amount", 1L),
            JSONObject.of("amount", new BigDecimal("1.00")), JSONObject.of("amount", "1"),
            JSONObject.of("amount", null), JSONObject.of("amount", null));
        assertEquals(2L, compile(q).evaluate(rows).getJSONObject(0).getLongValue("count"));
        assertEquals(0L, compile(q).evaluate(new JSONArray()).getJSONObject(0).getLongValue("count"));
    }

    @Test void preservesDatetimeTypesAcrossTheTemporaryBoundary() {
        JSONObject q = query();
        steps(q).getJSONObject(0).getJSONObject("output").put("fields", JSONArray.of(field("i", "when", "time")));
        steps(q).set(1, step("/temp01", "t", JSONArray.of(field("t", "time", "time")), null));
        steps(q).getJSONObject(1).getJSONObject("graph").getJSONArray("patterns").getJSONObject(0)
            .getJSONArray("objects").getJSONObject(0).put("conditions", JSONObject.of("properties", JSONObject.of(
                "field", "time", "operator", "=", "value", "2026-01-15T10:30:00Z")));
        assertEquals(1, compile(q).evaluate(JSONArray.of(JSONObject.of("time", "2026-01-15T18:30:00+08:00"),
            JSONObject.of("time", null))).size());
    }

    @Test void localGroupingHavingAndSortingRunBeforeFinalPagination() {
        JSONObject q = query();
        JSONObject group = field("t", "id", "group");
        JSONObject sum = field("t", "amount", "total"); sum.put("function", "sum");
        JSONObject output = steps(q).getJSONObject(1).getJSONObject("output");
        output.put("fields", JSONArray.of(group, sum));
        JSONObject key = JSONObject.of("variable", "t", "field", "id");
        JSONObject having = JSONObject.of("variable", "t", "field", "amount", "function", "sum", "operator", ">", "value", 10);
        output.put("group_by", JSONObject.of("fields", JSONArray.of(key), "having", JSONArray.of(having)));
        output.put("sort", JSONObject.of("fields", JSONArray.of(JSONObject.of("variable", "t", "field", "amount", "function", "sum", "order", "desc"))));
        output.put("limit", JSONObject.of("offset", 1, "count", 1));
        JSONArray rows = JSONArray.of(JSONObject.of("id", "A", "amount", 10), JSONObject.of("id", "A", "amount", 20),
            JSONObject.of("id", "B", "amount", 8), JSONObject.of("id", "B", "amount", 10), JSONObject.of("id", "C", "amount", 5));
        JSONObject result = compile(q).evaluate(rows).getJSONObject(0);
        assertEquals("B", result.getString("group")); assertEquals(0, new BigDecimal("18").compareTo((BigDecimal)result.get("total")));
    }

    @Test void supportsFourStepChainAndRejectsBranchesDuplicateNamesAndExtraSteps() {
        JSONObject q = query();
        steps(q).set(1, step("/temp01", "t", JSONArray.of(field("t", "id", "alias")), "/temp02"));
        steps(q).add(step("/temp02", "u", JSONArray.of(field("u", "alias", "alias")), "/temp03"));
        steps(q).add(step("/temp03", "v", JSONArray.of(field("v", "alias", "final")), null));
        assertEquals("original", compile(q).evaluate(JSONArray.of(JSONObject.of("id", "original"))).getJSONObject(0).getString("final"));
        JSONObject bad = JSON.parseObject(q.toJSONString());
        steps(bad).getJSONObject(2).getJSONObject("output").put("save_table", "/temp01");
        assertThrows(UnsupportedOperationException.class, () -> compile(bad));
        steps(q).add(step("/temp03", "v", JSONArray.of(field("v", "alias", "final")), null));
        assertThrows(UnsupportedOperationException.class, () -> compile(q));
    }

    @Test void refusesSentinelSizedIntermediateInsteadOfProducingPartialAggregates() {
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 5000; i++) rows.add(JSONObject.of("id", "id-" + i));
        assertThrows(UnsupportedOperationException.class, () -> compile(query()).evaluate(rows));
        rows.remove(rows.size() - 1);
        assertEquals(100, compile(query()).evaluate(rows).size(), "Final default is still 100");
    }

    @Test void rejectsUnsupportedChainsBeforeAnyExecution() {
        List<java.util.function.Consumer<JSONObject>> changes = List.of(
            q -> steps(q).getJSONObject(0).getJSONObject("output").put("limit", JSONObject.of("count", 10)),
            q -> steps(q).getJSONObject(0).getJSONObject("output").put("to_user", true),
            q -> steps(q).getJSONObject(0).getJSONObject("output").put("save_table", "/test/item"),
            q -> steps(q).getJSONObject(1).getJSONObject("output").put("save_table", "/extra"),
            q -> steps(q).getJSONObject(1).getJSONObject("output").getJSONArray("fields").getJSONObject(0).put("field", "missing"),
            q -> steps(q).getJSONObject(1).getJSONObject("output").getJSONArray("fields").getJSONObject(0).put("variable", "foreign"),
            q -> steps(q).getJSONObject(1).getJSONObject("output").getJSONArray("fields").getJSONObject(0).put("expr", "1"),
            q -> steps(q).getJSONObject(1).getJSONObject("graph").getJSONArray("patterns").getJSONObject(0).getJSONArray("objects")
                .getJSONObject(0).put("class", "/test/item"),
            q -> steps(q).getJSONObject(1).getJSONObject("graph").getJSONArray("patterns").getJSONObject(0).getJSONArray("objects")
                .add(JSONObject.of("class", "/test/item", "variable", "other")));
        for (var change : changes) {
            JSONObject q = query(); change.accept(q);
            assertThrows(RuntimeException.class, () -> compile(q), JSON.toJSONString(q, Feature.WriteMapNullValue));
        }
    }

    @Test void relationshipSourceDistinctDisablesPartialBindingWindow() {
        OnnCoreSchema graphSchema = new OnnCoreSchema(OnnCoreGraphCompilerTest.schemaWire());
        JSONObject q = OnnCoreGraphCompilerTest.query();
        JSONObject source = steps(q).getJSONObject(0);
        source.getJSONObject("output").remove("limit");
        source.getJSONObject("output").put("to_user", false); source.getJSONObject("output").put("save_table", "/temp01");
        source.getJSONObject("output").put("fields", JSONArray.of(field("b", "id", "id")));
        source.getJSONObject("output").getJSONArray("fields").getJSONObject(0).put("distinct", true);
        steps(q).add(step("/temp01", "t", JSONArray.of(field("t", "id", "product")), null));
        OnnCoreQueryPlan plan = new OnnCoreQueryPlan(q, graphSchema, graphSchema.ontology());
        OnnCoreGraphCompiler first = new OnnCoreGraphCompiler(plan.source, graphSchema, graphSchema.ontology(), true);
        assertNull(first.window());
        JSONArray rows = first.evaluate(List.of(OnnCoreGraphCompilerTest.binding("c1", "p1"),
            OnnCoreGraphCompilerTest.binding("c2", "p1"), OnnCoreGraphCompilerTest.binding("c3", "p2")));
        assertEquals(List.of("p1", "p2"), plan.evaluate(rows).stream().map(JSONObject::from).map(r -> r.getString("product")).toList());
    }
}
