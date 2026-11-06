package io.ontomato.dataengine.dataAdapter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.*;

/** Lowers equivalent count aggregates; precision-sensitive numeric aggregates remain residual. */
final class OnnCoreAggregatePlan {
    final JSONObject returns;
    private final OnnCoreDslCompiler finish;
    private final Map<String, String> keys;
    private final boolean grouped;

    private OnnCoreAggregatePlan(JSONObject returns, OnnCoreDslCompiler finish, Map<String, String> keys, boolean grouped) {
        this.returns = returns; this.finish = finish; this.keys = keys; this.grouped = grouped;
    }
    static OnnCoreAggregatePlan compile(JSONObject dsl, OnnCoreSchema schema, Map<String, String> classes) {
        if (classes.keySet().stream().anyMatch(variable -> !variable.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))) return null;
        JSONObject output = dsl.getJSONObject("answer").getJSONArray("steps").getJSONObject(0).getJSONObject("output");
        JSONArray fields = output.getJSONArray("fields");
        if (fields.stream().map(JSONObject::from).noneMatch(f -> f.getString("function") != null)) return null;
        JSONObject grouping = output.getJSONObject("group_by");
        JSONArray groups = grouping == null ? new JSONArray() : grouping.getJSONArray("fields");
        if (groups == null) groups = new JSONArray();
        if (grouping != null && grouping.getJSONArray("having") != null && !grouping.getJSONArray("having").isEmpty()) return null;
        // ONN currently merges numeric-looking strings and does not normalize datetime group keys.
        for (Object raw : groups) {
            JSONObject field = JSONObject.from(raw);
            String type = schema.dataType(classes.get(variable(field, classes)), field.getString("field"));
            if (!Set.of("int", "boolean").contains(type)) return null;
        }
        JSONObject sort = output.getJSONObject("sort");
        JSONArray sorts = sort == null ? new JSONArray() : sort.getJSONArray("fields");
        if (sorts == null) sorts = new JSONArray();
        // Full group-key order is needed to retain deterministic window semantics without binding IDs.
        if (!groups.isEmpty()) {
            Set<String> ordered = new HashSet<>();
            for (Object raw : sorts) { JSONObject f = JSONObject.from(raw); if (f.getString("function") == null) ordered.add(ref(f, schema, classes)); }
            for (Object raw : groups) if (!ordered.contains(ref(JSONObject.from(raw), schema, classes))) return null;
        }
        JSONArray metrics = new JSONArray(), groupRefs = new JSONArray(), attrs = new JSONArray();
        Map<String, String> keys = new LinkedHashMap<>(), remap = new LinkedHashMap<>();
        List<JSONObject> dependencies = new ArrayList<>();
        fields.forEach(raw -> dependencies.add(JSONObject.from(raw)));
        sorts.forEach(raw -> dependencies.add(JSONObject.from(raw)));
        for (Object raw : groups) {
            JSONObject field = JSONObject.from(raw); String reference = ref(field, schema, classes);
            if (!groupRefs.contains(reference)) groupRefs.add(reference);
        }
        for (JSONObject field : dependencies) {
            String signature = signature(field, classes);
            if (remap.containsKey(signature)) continue;
            String column = "v" + remap.size(), fn = field.getString("function");
            String variable = variable(field, classes), cls = classes.get(variable), name = field.getString("field");
            String source = schema.sourceField(cls, name), type = schema.dataType(cls, name);
            if ("id".equals(source)) return null;
            String nativeKey;
            if (fn == null || fn.isBlank()) {
                nativeKey = ref(field, schema, classes);
                if (!groupRefs.contains(nativeKey)) return null;
            } else {
                if (!fn.equals("count")) return null;
                boolean distinct = Boolean.TRUE.equals(field.get("distinct"));
                // Attribute distinct uses value normalization in Core; only booleans/integers agree.
                if (distinct && source != null && !Set.of("int", "boolean").contains(type)) return null;
                JSONObject metric = JSONObject.of("name", column, "type", distinct && source != null ? "count_distinct" : "count",
                    "of", "$" + variable, "over", distinct && source == null ? "objects" : "paths");
                if (source != null) metric.put("attribute", "$" + variable + "." + source);
                metrics.add(metric); nativeKey = column; type = "int";
            }
            keys.put(column, nativeKey); remap.put(signature, column);
            attrs.add(JSONObject.of("name", column, "dataType", type));
        }
        JSONObject finalOutput = JSON.parseObject(JSON.toJSONString(output, com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue));
        finalOutput.remove("group_by");
        for (Object raw : finalOutput.getJSONArray("fields")) rewrite(JSONObject.from(raw), remap, classes);
        JSONObject finalSort = finalOutput.getJSONObject("sort");
        if (finalSort != null) for (Object raw : finalSort.getJSONArray("fields")) rewrite(JSONObject.from(raw), remap, classes);
        String table = "/adapter/aggregates";
        OnnCoreSchema aggregateSchema = new OnnCoreSchema(JSONObject.of("ontologies", JSONArray.of(JSONObject.of("path", table, "attributes", attrs)), "relations", new JSONArray()));
        JSONObject finishDsl = JSONObject.of("answer", JSONObject.of("steps", JSONArray.of(JSONObject.of(
            "graph", JSONObject.of("patterns", JSONArray.of(JSONObject.of("objects", JSONArray.of(JSONObject.of("class", table, "variable", "row"))))),
            "output", finalOutput))));
        return new OnnCoreAggregatePlan(JSONObject.of("group_by", groupRefs, "metrics", metrics),
            new OnnCoreDslCompiler(finishDsl, aggregateSchema, aggregateSchema.ontology()), keys, !groups.isEmpty());
    }
    JSONArray evaluate(OnnCoreSnapshotReader.Result result) {
        List<JSONObject> nativeRows = result.rows();
        if (!grouped) {
            if (!nativeRows.isEmpty() || result.metrics() == null) throw new IllegalStateException("ONN global return is missing metrics");
            nativeRows = List.of(result.metrics());
        }
        List<JSONObject> rows = new ArrayList<>();
        for (JSONObject nativeRow : nativeRows) {
            JSONObject row = JSONObject.of("id", nativeRow.toJSONString());
            keys.forEach((column, key) -> {
                if (!nativeRow.containsKey(key)) throw new IllegalStateException("ONN aggregate field is missing: " + key);
                if (returns.getJSONArray("metrics").stream().map(JSONObject::from).anyMatch(metric -> key.equals(metric.getString("name")))) {
                    try {
                        if (new java.math.BigDecimal(nativeRow.get(key).toString()).longValueExact() < 0) throw new ArithmeticException();
                    } catch (RuntimeException invalid) { throw new IllegalStateException("ONN returned an invalid exact count", invalid); }
                }
                row.put(column, nativeRow.get(key));
            });
            rows.add(row);
        }
        return finish.evaluate(rows);
    }
    private static void rewrite(JSONObject field, Map<String, String> columns, Map<String, String> classes) {
        String column = columns.get(signature(field, classes));
        field.put("variable", "row"); field.put("field", column); field.remove("function"); field.remove("distinct");
    }
    private static String signature(JSONObject f, Map<String, String> classes) {
        return JSON.toJSONString(Arrays.asList(variable(f, classes), f.getString("field"), f.getString("function"), Boolean.TRUE.equals(f.get("distinct"))));
    }
    private static String variable(JSONObject f, Map<String, String> classes) {
        String variable = f.getString("variable");
        if (variable == null && classes.size() == 1) return classes.keySet().iterator().next();
        return variable;
    }
    private static String ref(JSONObject field, OnnCoreSchema schema, Map<String, String> classes) {
        String variable = variable(field, classes), source = schema.sourceField(classes.get(variable), field.getString("field"));
        return "$" + variable + (source == null ? "" : "." + source);
    }
}
