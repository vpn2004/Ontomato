package io.ontomato.dataengine.dataAdapter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.alibaba.fastjson2.JSON;

/** Validates read-only schema imports while retaining backend mappings and product display metadata. */
public final class OntologySchemaValidator {
    private OntologySchemaValidator() { }

    public static Map<String, Object> mergeDisplayMetadata(Map<String, Object> backend, Map<String, Object> submitted) {
        if (!structure(backend).equals(structure(submitted))) {
            throw new UnsupportedOperationException("Read-only adapter ontology must match the current backend schema; only display metadata can be edited");
        }
        Map<String, Object> merged = JSON.parseObject(JSON.toJSONString(backend));
        merged.put("datasetdesc", submitted.get("datasetdesc"));
        Map<String, Map<String, Object>> submittedClasses = indexed(submitted.get("classDef"), "className");
        for (Map<String, Object> classDef : list(merged.get("classDef"))) {
            Map<String, Object> incoming = submittedClasses.get(classDef.get("className"));
            copy(incoming, classDef, "showName", "classDesc", "classToCard", "instanceToCard", "inStarChart");
            Map<String, Map<String, Object>> incomingAttrs = indexed(incoming.get("attrs"), "name");
            for (Map<String, Object> attr : list(classDef.get("attrs"))) {
                Map<String, Object> incomingAttr = incomingAttrs.get(attr.get("name"));
                copy(incomingAttr, attr, "showName", "attrDesc", "timeFormat", "shouldReturn", "bizzkey");
                if (Boolean.FALSE.equals(attr.get("enable")) && Boolean.TRUE.equals(attr.get("bizzkey"))) {
                    throw new UnsupportedOperationException("Disabled backend attributes cannot be used as business keys");
                }
                Map<String, Map<String, Object>> incomingIndicators = indexed(incomingAttr.get("indicators"), "name");
                for (Map<String, Object> indicator : list(attr.get("indicators"))) {
                    copy(incomingIndicators.get(indicator.get("name")), indicator, "desc", "unit");
                }
            }
        }
        Map<String, Map<String, Object>> incomingRelations = relations(submitted);
        for (Map.Entry<String, Map<String, Object>> entry : relations(merged).entrySet()) {
            copy(incomingRelations.get(entry.getKey()), entry.getValue(), "desc", "fromdesc", "todesc");
        }
        return merged;
    }

    private static Map<String, Object> structure(Map<String, Object> ontology) {
        if (ontology == null) throw new IllegalArgumentException("Backend ontology is missing");
        Map<String, Map<String, Object>> classes = indexed(ontology.get("classDef"), "className");
        Object classList = ontology.get("classlist");
        if (!(classList instanceof List<?> names) || names.size() != new HashSet<>(names).size()
                || !new HashSet<>(names).equals(classes.keySet())) {
            throw new IllegalArgumentException("Ontology classlist must name each class exactly once");
        }
        Map<String, Object> result = new TreeMap<>();
        Map<String, Object> classShapes = new TreeMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : classes.entrySet()) {
            Map<String, Object> attrs = new TreeMap<>();
            for (Map.Entry<String, Map<String, Object>> attrEntry : indexed(entry.getValue().get("attrs"), "name").entrySet()) {
                Map<String, Object> attr = attrEntry.getValue();
                Map<String, Object> shape = new HashMap<>();
                shape.put("type", attr.get("type"));
                shape.put("sourceName", attr.get("sourceName"));
                shape.put("primaryKey", Boolean.TRUE.equals(attr.get("primaryKey")));
                shape.put("enable", !Boolean.FALSE.equals(attr.get("enable")));
                shape.put("permissionField", Boolean.TRUE.equals(attr.get("permissionField")));
                shape.put("indicators", indexed(attr.get("indicators"), "name").keySet());
                attrs.put(attrEntry.getKey(), shape);
            }
            classShapes.put(entry.getKey(), attrs);
        }
        result.put("classes", classShapes);
        Map<String, Object> relationShapes = new TreeMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : relations(ontology).entrySet()) {
            Map<String, Object> relation = entry.getValue();
            if (!classes.containsKey(relation.get("fromclass")) || !classes.containsKey(relation.get("toclass"))) {
                throw new IllegalArgumentException("Relationship endpoints must be present in the ontology");
            }
            Map<String, Object> shape = new HashMap<>();
            for (String key : List.of("fromclass", "toclass", "fromField", "toField")) shape.put(key, relation.get(key));
            relationShapes.put(entry.getKey(), shape);
        }
        result.put("relations", relationShapes);
        return result;
    }

    private static Map<String, Map<String, Object>> indexed(Object value, String key) {
        Map<String, Map<String, Object>> result = new TreeMap<>();
        for (Map<String, Object> entry : list(value)) {
            Object name = entry.get(key);
            if (!(name instanceof String string) || string.isBlank() || result.putIfAbsent(string, entry) != null) {
                throw new IllegalArgumentException("Ontology contains a missing or duplicate " + key);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?>)) throw new IllegalArgumentException("Ontology definitions must be arrays");
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> relations(Map<String, Object> ontology) {
        Object value = ontology.get("relationship_rule");
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Ontology relationships must be an object");
        return (Map<String, Map<String, Object>>) value;
    }

    private static void copy(Map<String, Object> from, Map<String, Object> to, String... fields) {
        for (String field : fields) {
            if (from.containsKey(field)) to.put(field, from.get(field));
        }
    }
}
