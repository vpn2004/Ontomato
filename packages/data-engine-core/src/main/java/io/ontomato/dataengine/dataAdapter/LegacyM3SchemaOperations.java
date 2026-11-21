package io.ontomato.dataengine.dataAdapter;

import java.util.Map;

import com.alibaba.fastjson2.JSONArray;
import io.ontomato.dataengine.config.DataRagConfig;
import io.ontomato.dataengine.util.HttpRequestUtil;

/** Keeps the historical M3 wire protocol out of generic schema services. */
final class LegacyM3SchemaOperations {
    private LegacyM3SchemaOperations() { }

    static void createClass(Map<String, Object> classDef, DataRagConfig config) {
        String name = (String) classDef.get("className");
        String mql = "create class if not exists " + name
                + " ( id varchar ) with autosearch=true, version=false, key=manu, alias='"
                + classDef.get("classDesc") + "' , nickname='" + name.substring(1).replace("/", "_") + "';";
        HttpRequestUtil.getExecMQL(config, mql);
    }

    static void deleteClass(String name, DataRagConfig config) {
        JSONArray existing = HttpRequestUtil.getExecMQL(config,
                "select name from /system/class where name='" + name + "'");
        if (!existing.isEmpty() && isEmptyClass(name, config)) {
            HttpRequestUtil.getExecMQL(config, "drop class " + name + ";");
        }
    }

    static void createRelationship(String name, Map<String, String> relationDef, DataRagConfig config) {
        String fromClass = relationDef.get("fromclass");
        String namespace = fromClass.substring(1);
        namespace = namespace.substring(0, namespace.indexOf('/')).trim();
        HttpRequestUtil.getExecMQL(config, "create edge type if not exists " + namespace + "." + name + " ;");
    }

    static void addAttribute(String className, Map<String, Object> attrDef, DataRagConfig config) {
        String attrName = (String) attrDef.get("name");
        JSONArray fields = fields(className, config);
        if (fields == null || fields.contains(attrName)) return;
        String type = (String) attrDef.get("type");
        String wireType = switch (type) {
            case "varchar", "text", "int", "long", "double", "date", "timestamp" -> type;
            case "bucket" -> "bucket {\n        \"collect\": 10,\n        \"dict\": true,\n"
                    + "        \"slot\": 5,\n        \"ttl\": 0,\n        \"type\": \"promdb\",\n"
                    + "        \"version\": 1\n\t}";
            case "vector" -> "list<varchar>";
            default -> throw new IllegalArgumentException("Unsupported schema attribute type: " + type);
        };
        HttpRequestUtil.getExecMQL(config, "alter class " + className + " add column " + attrName + " " + wireType + " ;");
    }

    static void deleteAttribute(String className, String attrName, DataRagConfig config) {
        JSONArray fields = fields(className, config);
        if (fields != null && fields.contains(attrName) && isEmptyClass(className, config)) {
            HttpRequestUtil.getExecMQL(config, "alter class " + className + " drop column " + attrName + " ;");
        }
    }

    private static JSONArray fields(String className, DataRagConfig config) {
        JSONArray result = HttpRequestUtil.getExecMQL(config,
                "select fields from /system/class where name='" + className + "'");
        return result.isEmpty() ? null : result.getJSONObject(0).getJSONArray("fields");
    }

    private static boolean isEmptyClass(String className, DataRagConfig config) {
        JSONArray result = HttpRequestUtil.getExecMQL(config, "select count(id) as c from " + className);
        return result.getJSONObject(0).getInteger("c") == 0;
    }
}
