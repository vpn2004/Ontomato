package io.ontomato.dataengine.dataAdapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

class OnnCoreSchemaTest {
    private static final String CLASS = "/test/item";

    @Test
    void acceptsExplicitNullCollectionsFromGoButRejectsMissingCollectionKeys() {
        JSONObject noRelations = wire(ontology(CLASS, null, attribute("name", "string")));
        noRelations.put("relations", null);
        OnnCoreSchema schema = new OnnCoreSchema(noRelations);
        assertEquals(1, ((java.util.List<?>) schema.ontology().get("classlist")).size());
        assertEquals(new OnnCoreSchema(wire(ontology(CLASS, null, attribute("name", "string")))).fingerprint, schema.fingerprint);
        assertTrue(((java.util.List<?>) new OnnCoreSchema(JSONObject.of("ontologies", null, "relations", null)).ontology().get("classlist")).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new OnnCoreSchema(JSONObject.of("ontologies", new JSONArray())));
    }

    @Test
    void resolvesInheritedAttributesAndChildOverridesWithTheEffectiveType() {
        OnnCoreSchema schema = new OnnCoreSchema(wire(
            ontology("/test/parent", null, attribute("inherited", "string"), attribute("amount", "int")),
            ontology(CLASS, "/test/parent", attribute("amount", "float"), attribute("own", "boolean"))));
        assertEquals("inherited", schema.sourceField(CLASS, "inherited"));
        assertEquals("float", projectedAttribute(schema, CLASS, "amount").getString("type"));
        assertEquals("boolean", projectedAttribute(schema, CLASS, "own").getString("type"));
        JSONObject row = schema.flatten(CLASS, instance("object:1", JSONObject.of("inherited", "parent value", "amount", 1.5, "own", true)));
        assertEquals("object:1", row.getString("id"));
        assertEquals("parent value", row.getString("inherited"));
        assertEquals(1.5, row.getDoubleValue("amount"));
    }

    @Test
    void rejectsMissingParentsInheritanceCyclesAndDuplicateDefinitions() {
        assertThrows(IllegalArgumentException.class, () -> new OnnCoreSchema(wire(ontology(CLASS, "/test/missing"))));
        assertThrows(IllegalArgumentException.class, () -> new OnnCoreSchema(wire(
            ontology(CLASS, "/test/parent"), ontology("/test/parent", CLASS))));
        assertThrows(IllegalArgumentException.class, () -> new OnnCoreSchema(wire(ontology(CLASS, null), ontology(CLASS, null))));
        assertThrows(IllegalArgumentException.class, () -> new OnnCoreSchema(wire(ontology(CLASS, null,
            attribute("same", "string"), attribute("same", "int")))));
    }

    @Test
    void rejectsUnknownClassesFieldsAndRelationEndpoints() {
        OnnCoreSchema schema = new OnnCoreSchema(wire(ontology(CLASS, null, attribute("name", "string"))));
        assertThrows(IllegalArgumentException.class, () -> schema.requireClass("/test/missing"));
        assertThrows(IllegalArgumentException.class, () -> schema.sourceField(CLASS, "missing"));
        JSONObject invalidRelation = wire(ontology(CLASS, null));
        invalidRelation.getJSONArray("relations").add(JSONObject.of("path", "/test/link", "sourceClass", CLASS, "targetClass", "/test/missing"));
        assertThrows(IllegalArgumentException.class, () -> new OnnCoreSchema(invalidRelation));
        assertThrows(IllegalArgumentException.class, () -> new OnnCoreSchema(JSONObject.of("ontologies", new JSONArray())));
    }

    @Test
    void importsUnsupportedAndUnspecifiedTypesAsDisabledMetadataWithoutScalarSemantics() {
        OnnCoreSchema schema = new OnnCoreSchema(wire(ontology(CLASS, null,
            attribute("complex", "json"), attribute("legacyText", "text"), attribute("legacyNumber", "double"),
            attribute("unspecified", ""), attribute("future", "unknown"), attribute("missingType", null), attribute("name", "string"))));
        for (String field : java.util.List.of("complex", "legacyText", "legacyNumber", "unspecified", "future", "missingType")) {
            JSONObject projected = projectedAttribute(schema, CLASS, field);
            assertFalse(projected.getBooleanValue("enable"));
            assertEquals(field, projected.getString("sourceName"));
            assertTrue(projected.getString("attrDesc").contains("unavailable"));
            assertThrows(UnsupportedOperationException.class, () -> schema.sourceField(CLASS, field));
        }
        JSONObject values = JSONObject.of("complex", JSONObject.of("nested", true), "legacyText", "text", "legacyNumber", 1.5,
            "unspecified", array(1, 2), "future", "future value", "missingType", "missing", "name", "safe");
        JSONObject row = schema.flatten(CLASS, instance("one", values));
        assertEquals(Set.of("id", "name"), row.keySet());
        assertEquals("safe", row.getString("name"));
    }

    @Test
    void acceptsPlainUnicodeClassAndRelationNamesWithoutRewritingSourcePaths() {
        String source = "物流运单类", target = "车辆";
        JSONObject wire = wire(ontology(source, null, attribute("运单号", "string")), ontology(target, null));
        wire.getJSONArray("relations").add(JSONObject.of("path", "运输车辆", "sourceClass", source, "targetClass", target));
        OnnCoreSchema schema = new OnnCoreSchema(wire);
        schema.requireClass(source);
        assertEquals("运单号", schema.sourceField(source, "运单号"));
        assertEquals("运单号", projectedAttribute(schema, source, "运单号").getString("sourceName"));
        JSONObject row = schema.flatten(source, JSONObject.of("classPath", source, "id", "运单:42", "attributes", JSONObject.of("运单号", "SO-42")));
        assertEquals("运单:42", row.getString("id"));
        assertEquals("SO-42", row.getString("运单号"));
        assertTrue(JSONObject.from(schema.ontology()).getJSONObject("relationship_rule").containsKey("运输车辆"));
        for (String invalid : java.util.List.of("", " ", "/", "class:identity", "../class", "class?", "class\n")) {
            assertThrows(IllegalArgumentException.class, () -> OnnCoreSchema.validatePath(invalid));
        }
    }

    @Test
    void omitsUnboundRelationsWithAnExplicitWarningWithoutInventingEndpointClasses() {
        JSONObject wire = wire(ontology(CLASS, null));
        wire.getJSONArray("relations").add(JSONObject.of("path", "/test/unbound", "sourceClass", "", "targetClass", ""));
        wire.getJSONArray("relations").add(JSONObject.of("path", "/test/half-bound", "sourceClass", CLASS));
        OnnCoreSchema schema = new OnnCoreSchema(wire);
        assertTrue(JSONObject.from(schema.ontology()).getJSONObject("relationship_rule").isEmpty());
        assertTrue(schema.ontology().get("datasetdesc").toString().contains("unbound relationships omitted: /test/half-bound, /test/unbound"));
        assertEquals(java.util.List.of(CLASS), schema.ontology().get("classlist"));
        assertThrows(IllegalArgumentException.class, () -> {
            wire.getJSONArray("relations").add(JSONObject.of("path", "/test/unbound", "sourceClass", "", "targetClass", ""));
            new OnnCoreSchema(wire);
        });
    }

    @Test
    void vectorAndDerivedFieldsAreDisabledAndCannotBeUsedAsScalarFields() {
        JSONObject derived = attribute("derived", "int"); derived.put("derived", JSONObject.of("aggregation", "count"));
        OnnCoreSchema schema = new OnnCoreSchema(wire(ontology(CLASS, null, attribute("embedding", "vector"), derived, attribute("name", "string"))));
        assertFalse(projectedAttribute(schema, CLASS, "embedding").getBooleanValue("enable"));
        assertFalse(projectedAttribute(schema, CLASS, "derived").getBooleanValue("enable"));
        assertThrows(UnsupportedOperationException.class, () -> schema.sourceField(CLASS, "embedding"));
        assertThrows(UnsupportedOperationException.class, () -> schema.sourceField(CLASS, "derived"));
        JSONObject row = schema.flatten(CLASS, instance("one", JSONObject.of("embedding", array(1, 2), "derived", 7, "name", "safe")));
        assertFalse(row.containsKey("embedding"));
        assertFalse(row.containsKey("derived"));
        assertEquals("safe", row.getString("name"));
    }

    @Test
    void keepsVirtualIdentitySeparateFromEveryCollidingBusinessIdField() {
        OnnCoreSchema schema = new OnnCoreSchema(wire(ontology(CLASS, null, attribute("id", "string"),
            attribute("onn_attr_id", "string"), attribute("onn_attr_id_", "string"))));
        JSONObject row = schema.flatten(CLASS, instance("identity:with:colons", JSONObject.of(
            "id", "business-id", "onn_attr_id", "original", "onn_attr_id_", "also-original")));
        assertEquals("identity:with:colons", row.getString("id"));
        assertEquals("business-id", row.getString("onn_attr_id__"));
        assertEquals("original", row.getString("onn_attr_id"));
        assertEquals("also-original", row.getString("onn_attr_id_"));
        assertNull(schema.sourceField(CLASS, "id"));
        assertEquals("id", schema.sourceField(CLASS, "onn_attr_id__"));
        assertTrue(projectedAttribute(schema, CLASS, "id").getBooleanValue("primaryKey"));
        assertEquals("@identity", projectedAttribute(schema, CLASS, "id").getString("sourceName"));
        assertEquals("id", projectedAttribute(schema, CLASS, "onn_attr_id__").getString("sourceName"));
    }

    @Test
    void rejectsOldMappingWhenAnIdAliasNowRefersToAnotherSourceAttribute() {
        OnnCoreSchema before = new OnnCoreSchema(wire(ontology(CLASS, null, attribute("id", "string"))));
        Map<String, Object> imported = before.ontology();
        OnnCoreSchema after = new OnnCoreSchema(wire(ontology(CLASS, null, attribute("id", "string"), attribute("onn_attr_id", "string"))));
        assertThrows(IllegalStateException.class, () -> after.validateMapping(imported, CLASS, Set.of("onn_attr_id")));
        after.validateMapping(after.ontology(), CLASS, Set.of("id", "onn_attr_id", "onn_attr_id_"));
    }

    @Test
    void rejectsMissingSourceMetadataDisabledFieldsAndChangedTypes() {
        OnnCoreSchema schema = new OnnCoreSchema(wire(ontology(CLASS, null, attribute("name", "string"))));
        JSONObject missingSource = copy(schema.ontology());
        projectedAttribute(missingSource, CLASS, "name").remove("sourceName");
        assertThrows(IllegalStateException.class, () -> schema.validateMapping(missingSource, CLASS, Set.of("name")));
        JSONObject disabled = copy(schema.ontology());
        projectedAttribute(disabled, CLASS, "name").put("enable", false);
        assertThrows(IllegalStateException.class, () -> schema.validateMapping(disabled, CLASS, Set.of("name")));
        JSONObject changedType = copy(schema.ontology());
        projectedAttribute(changedType, CLASS, "name").put("type", "int");
        assertThrows(IllegalStateException.class, () -> schema.validateMapping(changedType, CLASS, Set.of("name")));
        JSONObject changedIdentity = copy(schema.ontology());
        projectedAttribute(changedIdentity, CLASS, "id").put("sourceName", "id");
        assertThrows(IllegalStateException.class, () -> schema.validateMapping(changedIdentity, CLASS, Set.of("id")));
    }

    @Test
    void rejectsMissingOrMismatchedInstanceIdentityAndMissingAttributes() {
        OnnCoreSchema schema = new OnnCoreSchema(wire(ontology(CLASS, null)));
        assertThrows(IllegalStateException.class, () -> schema.flatten(CLASS, null));
        assertThrows(IllegalStateException.class, () -> schema.flatten(CLASS, JSONObject.of("id", "one", "classPath", "/test/other", "attributes", new JSONObject())));
        assertThrows(IllegalStateException.class, () -> schema.flatten(CLASS, JSONObject.of("classPath", CLASS, "attributes", new JSONObject())));
        assertThrows(IllegalStateException.class, () -> schema.flatten(CLASS, JSONObject.of("id", "one", "classPath", CLASS)));
    }

    @Test
    void datetimeValuesBecomeInstantsForRfc3339DatesAndUnixSeconds() {
        OnnCoreSchema schema = new OnnCoreSchema(wire(ontology(CLASS, null, attribute("when", "datetime"))));
        Instant midnight = Instant.parse("2026-10-09T00:00:00Z");
        assertEquals(midnight, schema.temporalValue(CLASS, "when", "2026-10-09T08:00:00+08:00"));
        assertEquals(midnight, schema.temporalValue(CLASS, "when", "2026-10-09"));
        assertEquals(midnight, schema.temporalValue(CLASS, "when", midnight.getEpochSecond()));
        assertEquals(midnight, schema.temporalValue(CLASS, "when", midnight.toEpochMilli()));
        assertEquals(midnight, schema.temporalValue(CLASS, "when", "2026-10-09 00:00:00"));
        assertNull(schema.temporalValue(CLASS, "when", null));
        assertThrows(IllegalArgumentException.class, () -> schema.temporalValue(CLASS, "when", "not-a-date"));
    }

    @Test
    void fingerprintIsIndependentOfWireDefinitionAndAttributeOrdering() {
        OnnCoreSchema first = new OnnCoreSchema(wire(ontology(CLASS, null, attribute("b", "int"), attribute("a", "string")),
            ontology("/test/other", null, attribute("name", "string"))));
        OnnCoreSchema second = new OnnCoreSchema(wire(ontology("/test/other", null, attribute("name", "string")),
            ontology(CLASS, null, attribute("a", "string"), attribute("b", "int"))));
        assertEquals(first.fingerprint, second.fingerprint);
    }

    private static JSONObject projectedAttribute(OnnCoreSchema schema, String path, String name) {
        return projectedAttribute(JSONObject.from(schema.ontology()), path, name);
    }
    private static JSONObject projectedAttribute(JSONObject mapping, String path, String name) {
        JSONObject cls = mapping.getJSONArray("classDef").stream().map(JSONObject::from)
            .filter(candidate -> path.equals(candidate.getString("className"))).findFirst().orElseThrow();
        return cls.getJSONArray("attrs").stream().map(JSONObject::from)
            .filter(candidate -> name.equals(candidate.getString("name"))).findFirst().orElseThrow();
    }
    private static JSONObject copy(Map<String, Object> mapping) { return JSON.parseObject(JSON.toJSONString(mapping)); }
    private static JSONObject attribute(String name, String type) { return JSONObject.of("name", name, "dataType", type); }
    private static JSONObject ontology(String path, String parent, JSONObject... attributes) {
        return JSONObject.of("path", path, "name", path, "parentPath", parent, "attributes", array((Object[]) attributes));
    }
    private static JSONObject wire(JSONObject... classes) { return JSONObject.of("ontologies", array((Object[]) classes), "relations", new JSONArray()); }
    private static JSONObject instance(String id, JSONObject attributes) { return JSONObject.of("id", id, "classPath", CLASS, "attributes", attributes); }
    private static JSONArray array(Object... values) { JSONArray result = new JSONArray(); result.addAll(Arrays.asList(values)); return result; }
}
