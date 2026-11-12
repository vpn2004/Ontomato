package io.ontomato.dataengine.dataAdapter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OnnCoreGraphCompilerTest {
    static final String A = "/test/a", B = "/test/b", C = "/test/c", AB = "/test/ab", BC = "/test/bc";
    static JSONObject schemaWire() {
        JSONArray classes = new JSONArray();
        for (String cls : List.of(A, B, C)) classes.add(JSONObject.of("path", cls, "attributes", JSONArray.of(
            JSONObject.of("name", "name", "dataType", "string"), JSONObject.of("name", "when", "dataType", "datetime"))));
        return JSONObject.of("ontologies", classes, "relations", JSONArray.of(
            JSONObject.of("path", AB, "sourceClass", A, "targetClass", B),
            JSONObject.of("path", BC, "sourceClass", B, "targetClass", C)));
    }
    static JSONObject object(int index, String variable, String cls) { return JSONObject.of("idx", index, "variable", variable, "class", cls); }
    static JSONObject field(String variable, String field, String as) { return JSONObject.of("variable", variable, "field", field, "as", as); }
    static JSONObject link(int from, int to, String type) { return JSONObject.of("from", from, "to", to, "type", JSONArray.of(type)); }
    static JSONObject query() {
        return JSONObject.of("answer", JSONObject.of("steps", JSONArray.of(JSONObject.of("graph", JSONObject.of("patterns", JSONArray.of(JSONObject.of(
            "objects", JSONArray.of(object(7, "a", A), object(3, "b", B)), "relationship", JSONArray.of(link(7, 3, AB))))),
            "output", JSONObject.of("fields", JSONArray.of(field("a", "id", "source"), field("b", "id", "target")), "limit", JSONObject.of("count", 5000))))));
    }
    static JSONObject pattern(JSONObject q) { return step(q).getJSONObject("graph").getJSONArray("patterns").getJSONObject(0); }
    static JSONObject step(JSONObject q) { return q.getJSONObject("answer").getJSONArray("steps").getJSONObject(0); }
    static JSONObject bound(String cls, String id, JSONObject attributes) { return JSONObject.of("class_path", cls, "id", id, "attributes", attributes); }
    static JSONObject binding(String source, String target) {
        return JSONObject.of("a", bound(A, source, JSONObject.of("name", "A")), "b", bound(B, target, JSONObject.of("name", "B")));
    }
    private OnnCoreGraphCompiler compile(JSONObject q) { OnnCoreSchema s = new OnnCoreSchema(schemaWire()); return new OnnCoreGraphCompiler(q, s, s.ontology()); }

    @Test void compilesDirectionsAndNonSequentialIndicesWithoutMutatingTheDsl() {
        JSONObject q = query(), before = JSON.parseObject(q.toJSONString());
        OnnCoreGraphCompiler compiler = compile(q);
        JSONObject pipeline = compiler.nativePipeline();
        JSONObject second = pipeline.getJSONArray("steps").getJSONObject(1).getJSONObject("graph");
        assertEquals("a", second.getString("from_step"));
        assertFalse(second.containsKey("match_class"));
        assertEquals("out", second.getJSONObject("traverse").getString("direction"));
        assertEquals(B, second.getJSONObject("traverse").getJSONObject("next").getString("match_class"));
        assertEquals(before, q);
        Collections.reverse(pattern(q).getJSONArray("objects"));
        JSONObject inward = compile(q).nativePipeline().getJSONArray("steps").getJSONObject(1).getJSONObject("graph");
        assertEquals("b", inward.getString("from_step"));
        assertEquals("in", inward.getJSONObject("traverse").getString("direction"));
        assertEquals(compiler.evaluate(List.of(binding("same:id", "same:id"))), compile(q).evaluate(List.of(binding("same:id", "same:id"))));
    }

    @Test void keepsPathMultiplicityAndCountsDistinctObjectsOnlyWhenRequested() {
        JSONObject q = query();
        JSONObject output = step(q).getJSONObject("output");
        JSONObject paths = field("a", "id", "paths"); paths.put("function", "count");
        JSONObject objects = field("a", "id", "objects"); objects.put("function", "count"); objects.put("distinct", true);
        output.put("fields", JSONArray.of(paths, objects));
        JSONObject answer = compile(q).evaluate(List.of(binding("a:1", "b:1"), binding("a:1", "b:2"))).getJSONObject(0);
        assertEquals(2L, answer.getLongValue("paths")); assertEquals(1L, answer.getLongValue("objects"));
        assertEquals(0L, compile(q).evaluate(List.of()).getJSONObject(0).getLongValue("paths"));
    }

    @Test void handlesDateNullLogicGroupingAndWindowAfterBinding() {
        JSONObject q = query();
        JSONObject pred = JSONObject.of("field", "when", "operator", "=", "value", "2026-01-15T10:30:00Z");
        pattern(q).getJSONArray("objects").getJSONObject(1).put("conditions", JSONObject.of("properties", pred));
        var compiler = compile(q);
        JSONObject row = binding("a:1", "b:1"); row.getJSONObject("b").getJSONObject("attributes").put("when", "2026-01-15T18:30:00+08:00");
        assertEquals(1, compiler.evaluate(List.of(row, binding("a:2", "b:2"))).size());
        assertFalse(compiler.nativePipeline().toJSONString().contains("where_logic"), "Date predicates stay residual");
        pred.put("operator", "is"); pred.put("value", null);
        assertEquals("a:2", compile(q).evaluate(List.of(row, binding("a:2", "b:2"))).getJSONObject(0).getString("source"));
        pred.put("operator", "=");
        assertTrue(compile(q).evaluate(List.of(binding("a:2", "b:2"))).isEmpty(), "SQL = null is unknown");
    }

    @Test void supportsMultihopAndRejectsSemanticShapesThatWouldLoseRows() {
        JSONObject q = query();
        pattern(q).getJSONArray("objects").add(object(9, "c", C));
        pattern(q).getJSONArray("relationship").add(link(3, 9, BC));
        assertEquals(3, compile(q).nativePipeline().getJSONArray("steps").size());
        for (String change : List.of("same_class", "union", "unbounded", "optional", "wrong_direction", "ambiguous", "duplicate", "bad_index")) {
            JSONObject bad = query();
            switch (change) {
                case "same_class" -> pattern(bad).getJSONArray("objects").getJSONObject(1).put("class", A);
                case "union" -> pattern(bad).getJSONArray("relationship").getJSONObject(0).getJSONArray("type").add(BC);
                case "unbounded" -> pattern(bad).getJSONArray("relationship").getJSONObject(0).put("max_hops", -1);
                case "optional" -> pattern(bad).getJSONArray("relationship").getJSONObject(0).put("optional", true);
                case "wrong_direction" -> pattern(bad).getJSONArray("relationship").getJSONObject(0).put("from", 3);
                case "ambiguous" -> step(bad).getJSONObject("output").getJSONArray("fields").getJSONObject(0).remove("variable");
                case "duplicate" -> pattern(bad).getJSONArray("objects").getJSONObject(1).put("variable", "a");
                case "bad_index" -> pattern(bad).getJSONArray("objects").getJSONObject(1).put("idx", 1.5);
            }
            assertThrows(RuntimeException.class, () -> compile(bad), change);
        }
    }

    @Test void acceptsPluralSpellingButRejectsDisabledOrDriftedRelationshipsAndFields() {
        JSONObject q = query(); pattern(q).put("relationships", pattern(q).remove("relationship"));
        assertEquals(2, compile(q).nativePipeline().getJSONArray("steps").size());
        OnnCoreSchema s = new OnnCoreSchema(schemaWire());
        for (String change : List.of("disabled", "mapping", "field")) {
            JSONObject imported = JSONObject.from(s.ontology());
            imported = JSON.parseObject(imported.toJSONString());
            if (change.equals("disabled")) imported.getJSONObject("relationship_rule").getJSONObject(AB).put("enable", false);
            if (change.equals("mapping")) imported.getJSONObject("relationship_rule").getJSONObject(AB).put("toField", "name");
            if (change.equals("field")) imported.getJSONArray("classDef").getJSONObject(0).getJSONArray("attrs").getJSONObject(0).put("enable", false);
            Map<String, Object> mapping = imported;
            assertThrows(IllegalStateException.class, () -> new OnnCoreGraphCompiler(q, s, mapping), change);
        }
    }

    @Test void relationshipFailuresNameTheDefinedDirectionAndUsableRelationship() {
        OnnCoreSchema schema = new OnnCoreSchema(schemaWire());
        Map<String, Object> mapping = schema.ontology();
        OnnCoreMappingView view = new OnnCoreMappingView(mapping);
        IllegalArgumentException wrongDirection = assertThrows(IllegalArgumentException.class,
            () -> view.validateRelationship(schema, AB, B, A));
        assertTrue(wrongDirection.getMessage().contains(A + " --" + AB + "--> " + B));
        assertTrue(wrongDirection.getMessage().contains(B + " -> " + A));

        IllegalStateException unknown = assertThrows(IllegalStateException.class,
            () -> view.validateRelationship(schema, "/test/missing", A, B));
        assertTrue(unknown.getMessage().contains("Available enabled relationships"));
        assertTrue(unknown.getMessage().contains(AB));
    }
}
