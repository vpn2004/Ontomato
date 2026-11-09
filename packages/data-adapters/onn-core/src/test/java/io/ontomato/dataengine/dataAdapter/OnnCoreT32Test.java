package io.ontomato.dataengine.dataAdapter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OnnCoreT32Test {
    @Test void mappingViewFreezesOneRequestAndPreservesDisabledFields() {
        OnnCoreSchema schema = new OnnCoreSchema(OnnCoreGraphCompilerTest.schemaWire());
        JSONObject mapping = JSON.parseObject(JSON.toJSONString(schema.ontology()));
        OnnCoreMappingView view = new OnnCoreMappingView(mapping);
        view.validate(schema, OnnCoreGraphCompilerTest.A, Set.of("id", "name"));
        mapping.getJSONArray("classDef").getJSONObject(0).getJSONArray("attrs").getJSONObject(0).put("enable", false);
        view.validate(schema, OnnCoreGraphCompilerTest.A, Set.of("id"));
        assertThrows(IllegalStateException.class, () -> new OnnCoreMappingView(mapping).validate(schema, OnnCoreGraphCompilerTest.A, Set.of("id")));
    }

    @Test void narrowFlattenMatchesFullProjectionAndPreservesMissingAndDate() {
        OnnCoreSchema schema = new OnnCoreSchema(OnnCoreGraphCompilerTest.schemaWire());
        JSONObject row = JSONObject.of("classPath", OnnCoreGraphCompilerTest.A, "id", "id:1", "attributes", JSONObject.of("name", "A", "when", "2026-01-15"));
        JSONObject full = schema.flatten(OnnCoreGraphCompilerTest.A, row);
        JSONObject narrow = schema.flatten(OnnCoreGraphCompilerTest.A, row, Set.of("name"));
        assertEquals(Set.of("id", "name"), narrow.keySet());
        assertEquals(full.get("name"), narrow.get("name"));
        row.getJSONObject("attributes").remove("name");
        assertTrue(schema.flatten(OnnCoreGraphCompilerTest.A, row, Set.of("name")).containsKey("name"));
        assertNull(schema.flatten(OnnCoreGraphCompilerTest.A, row, Set.of("name")).get("name"));
    }

    @Test void graphReportsSingleMappingParseAndNarrowFlattenWork() {
        OnnCoreSchema schema = new OnnCoreSchema(OnnCoreGraphCompilerTest.schemaWire());
        JSONObject query = OnnCoreGraphCompilerTest.query();
        OnnCoreGraphCompilerTest.step(query).getJSONObject("output").put("fields", JSONArray.of(OnnCoreGraphCompilerTest.field("a", "name", "source")));
        var timing = new OnnCoreTiming();
        var compiler = new OnnCoreGraphCompiler(query, schema, schema.ontology(), true, timing);
        JSONArray result = compiler.evaluate(List.of(OnnCoreGraphCompilerTest.binding("a:1", "b:1")));
        assertEquals("A", result.getJSONObject(0).getString("source"));
        assertEquals(1L, timing.counters().getLongValue("mappingParses"));
        assertEquals(1L, timing.counters().getLongValue("flattenFields"));
        assertTrue(timing.phases().getDoubleValue("mapping") >= 0);
        assertFalse(timing.phases().toJSONString().contains("a:1"));
    }

    @Test void graphKeepsEscapedIdentityOrderAndDoesNotMutateBindings() {
        OnnCoreSchema schema = new OnnCoreSchema(OnnCoreGraphCompilerTest.schemaWire());
        var compiler = new OnnCoreGraphCompiler(OnnCoreGraphCompilerTest.query(), schema, schema.ontology(), true);
        List<JSONObject> rows = List.of(OnnCoreGraphCompilerTest.binding("a\"1", "b\\2"), OnnCoreGraphCompilerTest.binding("a\n1", "b:1"));
        String before = JSON.toJSONString(rows);
        assertEquals(compiler.evaluate(rows), compiler.evaluate(rows));
        assertEquals(before, JSON.toJSONString(rows));
    }
}
