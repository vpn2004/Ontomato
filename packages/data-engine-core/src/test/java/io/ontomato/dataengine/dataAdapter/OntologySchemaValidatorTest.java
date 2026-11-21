package io.ontomato.dataengine.dataAdapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

class OntologySchemaValidatorTest {
    private static final String ONTOLOGY = """
            {"datasetdesc":"Backend","classlist":["Asset","Site"],"nativeVersion":"v1",
             "classDef":[
               {"className":"Asset","showName":"Asset","classDesc":"An asset","nativeClass":"asset-v1","attrs":[
                 {"name":"id","type":"varchar","primaryKey":true,"enable":true,"sourceName":"@identity","nativeAttribute":"object-id"},
                 {"name":"code","type":"varchar","enable":true,"bizzkey":false,"sourceName":"code","nativeAttribute":"code-v1"},
                 {"name":"metrics","type":"bucket","sourceName":"metrics","indicators":[{"name":"temperature","desc":"Temperature","unit":"C","nativeMetric":"t"}]}]},
               {"className":"Site","attrs":[{"name":"id","type":"varchar","primaryKey":true,"enable":true,"sourceName":"@identity"}]}],
             "relationship_rule":{"locatedAt":{"fromclass":"Asset","toclass":"Site","fromField":"id","toField":"id","desc":"Location","nativeRelation":"location-v1"}}}
            """;

    @Test
    void displayEditsKeepCurrentBackendMappingsAndDoNotMutateTheBackend() {
        JSONObject backend = ontology();
        String original = backend.toJSONString();
        JSONObject submitted = ontology();
        submitted.put("datasetdesc", "Our equipment");
        submitted.put("classlist", List.of("Site", "Asset"));
        submitted.getJSONArray("classDef").getJSONObject(0).put("showName", "Equipment");
        submitted.getJSONArray("classDef").getJSONObject(0).put("classDesc", "Maintained equipment");
        submitted.getJSONArray("classDef").getJSONObject(0).remove("nativeClass");
        attr(submitted, 1).put("attrDesc", "Business code");
        attr(submitted, 1).put("bizzkey", true);
        attr(submitted, 1).put("nativeAttribute", "untrusted-mapping");
        indicator(submitted).put("desc", "Measured temperature");
        indicator(submitted).put("unit", "°C");
        relation(submitted).put("desc", "Installed at");

        Map<String, Object> merged = OntologySchemaValidator.mergeDisplayMetadata(backend, submitted);
        JSONObject result = JSON.parseObject(JSON.toJSONString(merged));

        assertNotSame(backend, merged);
        assertEquals(original, backend.toJSONString());
        assertEquals("Our equipment", result.getString("datasetdesc"));
        assertEquals("v1", result.getString("nativeVersion"));
        assertEquals("Equipment", result.getJSONArray("classDef").getJSONObject(0).getString("showName"));
        assertEquals("asset-v1", result.getJSONArray("classDef").getJSONObject(0).getString("nativeClass"));
        assertEquals("Business code", attr(result, 1).getString("attrDesc"));
        assertEquals(true, attr(result, 1).getBoolean("bizzkey"));
        assertEquals("code-v1", attr(result, 1).getString("nativeAttribute"));
        assertEquals("°C", indicator(result).getString("unit"));
        assertEquals("t", indicator(result).getString("nativeMetric"));
        assertEquals("Installed at", relation(result).getString("desc"));
        assertEquals("location-v1", relation(result).getString("nativeRelation"));
    }

    @Test
    void alteredAttributesAndRelationshipsCannotBeImported() {
        List<Consumer<JSONObject>> changes = List.of(
                rule -> attr(rule, 1).put("type", "int"),
                rule -> attr(rule, 0).remove("sourceName"),
                rule -> attr(rule, 0).put("sourceName", "id"),
                rule -> attr(rule, 1).put("sourceName", "another_attribute"),
                rule -> attr(rule, 0).put("primaryKey", false),
                rule -> attr(rule, 1).put("enable", false),
                rule -> attr(rule, 1).put("permissionField", true),
                rule -> attr(rule, 1).put("name", "renamed"),
                rule -> indicator(rule).put("name", "pressure"),
                rule -> relation(rule).put("toclass", "Asset"),
                rule -> relation(rule).put("fromField", "code"),
                rule -> rule.getJSONObject("relationship_rule").put("renamed",
                        rule.getJSONObject("relationship_rule").remove("locatedAt")),
                rule -> rule.getJSONArray("classDef").remove(1));
        for (Consumer<JSONObject> change : changes) {
            JSONObject submitted = ontology();
            change.accept(submitted);
            assertThrows(RuntimeException.class,
                    () -> OntologySchemaValidator.mergeDisplayMetadata(ontology(), submitted));
        }
    }

    @Test
    void incompleteClassListsDuplicateAttributesAndMissingEndpointsAreRejected() {
        JSONObject missingClass = ontology();
        missingClass.put("classlist", List.of("Asset"));
        assertThrows(IllegalArgumentException.class,
                () -> OntologySchemaValidator.mergeDisplayMetadata(ontology(), missingClass));

        JSONObject extraClass = ontology();
        extraClass.put("classlist", List.of("Asset", "Site", "Extra"));
        extraClass.getJSONArray("classDef").add(Map.of("className", "Extra", "attrs", List.of()));
        assertThrows(UnsupportedOperationException.class,
                () -> OntologySchemaValidator.mergeDisplayMetadata(ontology(), extraClass));

        JSONObject duplicates = ontology();
        duplicates.getJSONArray("classDef").getJSONObject(0).getJSONArray("attrs").add(attr(duplicates, 1));
        assertThrows(IllegalArgumentException.class,
                () -> OntologySchemaValidator.mergeDisplayMetadata(ontology(), duplicates));

        JSONObject duplicateClasses = ontology();
        duplicateClasses.put("classlist", List.of("Asset", "Asset", "Site"));
        assertThrows(IllegalArgumentException.class,
                () -> OntologySchemaValidator.mergeDisplayMetadata(ontology(), duplicateClasses));

        JSONObject endpoint = ontology();
        relation(endpoint).put("toclass", "Missing");
        assertThrows(IllegalArgumentException.class,
                () -> OntologySchemaValidator.mergeDisplayMetadata(ontology(), endpoint));
    }

    @Test
    void disabledBackendAttributesCannotBecomeBusinessKeys() {
        JSONObject backend = ontology();
        attr(backend, 1).put("enable", false);
        String original = backend.toJSONString();
        JSONObject submitted = JSON.parseObject(original);
        attr(submitted, 1).put("bizzkey", true);

        assertThrows(UnsupportedOperationException.class,
                () -> OntologySchemaValidator.mergeDisplayMetadata(backend, submitted));
        assertEquals(original, backend.toJSONString());
    }

    @Test
    void existingDisabledBusinessKeysCanBeCleared() {
        JSONObject backend = ontology();
        attr(backend, 1).put("enable", false);
        attr(backend, 1).put("bizzkey", true);
        JSONObject submitted = JSON.parseObject(backend.toJSONString());
        attr(submitted, 1).put("bizzkey", false);

        JSONObject merged = JSON.parseObject(JSON.toJSONString(
                OntologySchemaValidator.mergeDisplayMetadata(backend, submitted)));

        assertEquals(false, attr(merged, 1).getBoolean("bizzkey"));
    }

    private static JSONObject ontology() {
        return JSON.parseObject(ONTOLOGY);
    }

    private static JSONObject attr(JSONObject rule, int index) {
        return rule.getJSONArray("classDef").getJSONObject(0).getJSONArray("attrs").getJSONObject(index);
    }

    private static JSONObject indicator(JSONObject rule) {
        return attr(rule, 2).getJSONArray("indicators").getJSONObject(0);
    }

    private static JSONObject relation(JSONObject rule) {
        return rule.getJSONObject("relationship_rule").getJSONObject("locatedAt");
    }
}
