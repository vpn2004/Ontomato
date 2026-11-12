package io.ontomato.dataengine.dataAdapter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.*;

// 单次编译冻结mapping内容，所有对象与关系校验共用一次解析。
final class OnnCoreMappingView {
    private final Map<String, Map<String, JSONObject>> attributes = new HashMap<>();
    private final JSONObject rules;

    OnnCoreMappingView(Map<String, Object> mapping) {
        if (mapping == null) throw new IllegalStateException("Import the ONN schema into the Ontology Manager first");
        JSONObject copy = JSON.parseObject(JSON.toJSONString(mapping, com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue));
        rules = copy.getJSONObject("relationship_rule");
        JSONArray defs = copy.getJSONArray("classDef");
        if (defs != null) for (Object raw : defs) {
            JSONObject cls = JSONObject.from(raw);
            Map<String, JSONObject> fields = new HashMap<>();
            JSONArray attrs = cls.getJSONArray("attrs");
            if (attrs == null) continue;
            for (Object field : attrs) {
                JSONObject attr = JSONObject.from(field);
                fields.put(attr.getString("name"), attr);
            }
            attributes.put(cls.getString("className"), fields);
        }
    }

    void validate(OnnCoreSchema schema, String path, Set<String> fields) {
        Map<String, JSONObject> imported = attributes.get(path);
        if (imported == null) throw new IllegalStateException("Import the ONN schema into the Ontology Manager first; class is absent: " + path);
        JSONObject expected = schema.mappingClass(path);
        for (Object raw : expected.getJSONArray("attrs")) {
            JSONObject attr = JSONObject.from(raw);
            String name = attr.getString("name");
            if (!fields.contains(name)) continue;
            JSONObject old = imported.get(name);
            if (old == null || Boolean.FALSE.equals(old.getBoolean("enable")) || !Objects.equals(old.getString("type"), attr.getString("type"))
                || !Objects.equals(old.getBoolean("primaryKey"), attr.getBoolean("primaryKey"))
                || !Objects.equals(old.getString("sourceName"), attr.getString("sourceName")))
                throw new IllegalStateException("ONN schema mapping changed or field is disabled; re-import schema: " + path + "." + name);
        }
        JSONObject id = imported.get("id");
        if (id == null || !Boolean.TRUE.equals(id.getBoolean("primaryKey")) || Boolean.FALSE.equals(id.getBoolean("enable"))
            || !"@identity".equals(id.getString("sourceName")) || !"varchar".equals(id.getString("type")))
            throw new IllegalStateException("ONN requires the virtual id as primary key; re-import schema");
    }

    void validateRelationship(OnnCoreSchema schema, String type, String from, String to) {
        JSONObject expected = schema.mappingRelationship(type);
        JSONObject imported = rules == null ? null : rules.getJSONObject(type);
        if (expected == null || imported == null || Boolean.FALSE.equals(imported.getBoolean("enable"))) {
            throw new IllegalStateException("ONN relationship is unknown, disabled or not imported: " + type
                + ". Available enabled relationships: " + availableRelationships(schema));
        }
        for (String key : java.util.List.of("fromclass", "toclass", "fromField", "toField"))
            if (!Objects.equals(expected.getString(key), imported.getString(key)))
                throw new IllegalStateException("ONN relationship mapping changed; re-import schema: " + type);
        if (!from.equals(expected.getString("fromclass")) || !to.equals(expected.getString("toclass")))
            throw new IllegalArgumentException("ONN relationship endpoint/direction mismatch at relationship.type[0]: requested "
                + from + " -> " + to + " via " + type + "; ontology defines " + expected.getString("fromclass") + " -> "
                + expected.getString("toclass") + ". Available enabled relationships: " + availableRelationships(schema));
    }

    private String availableRelationships(OnnCoreSchema schema) {
        JSONObject definitions = rules == null ? null : rules;
        if (definitions == null || definitions.isEmpty()) return "none; import enabled relationships in Ontology Manager";
        List<String> available = new ArrayList<>();
        for (String name : definitions.keySet()) {
            JSONObject imported = definitions.getJSONObject(name);
            JSONObject expected = schema.mappingRelationship(name);
            if (expected == null || imported == null || Boolean.FALSE.equals(imported.getBoolean("enable"))) continue;
            if (available.size() == 12) { available.add("..."); break; }
            available.add(expected.getString("fromclass") + " --" + name + "--> " + expected.getString("toclass"));
        }
        return available.isEmpty() ? "none; import enabled relationships in Ontology Manager" : available.toString();
    }
}
