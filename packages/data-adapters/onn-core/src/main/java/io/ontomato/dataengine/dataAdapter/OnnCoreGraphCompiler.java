package io.ontomato.dataengine.dataAdapter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.*;

/** Lowers typed inner joins to ONN bindings, then reuses the scalar DSL evaluator on a binding table. */
final class OnnCoreGraphCompiler {
    private static final String TABLE = "/adapter/bindings";
    private record Column(String variable, String field) { }
    private record Link(int from, int to, String type) { }
    private final OnnCoreSchema schema;
    private final Map<Integer, JSONObject> objects = new LinkedHashMap<>();
    private final Map<String, String> classes = new LinkedHashMap<>();
    private final Map<Column, String> columns = new LinkedHashMap<>();
    private final OnnCoreDslCompiler residual;
    private final JSONObject pipeline;
    private final boolean extended;
    private boolean exactFilters = true;
    private final OnnCoreAggregatePlan aggregatePlan;

    static boolean isGraph(JSONObject dsl) {
        JSONObject answer = dsl.getJSONObject("answer");
        JSONArray steps = answer == null ? null : answer.getJSONArray("steps");
        if (steps == null || steps.size() != 1) return false;
        JSONObject graph = steps.getJSONObject(0).getJSONObject("graph");
        JSONArray patterns = graph == null ? null : graph.getJSONArray("patterns");
        if (patterns == null || patterns.size() != 1) return false;
        JSONObject pattern = patterns.getJSONObject(0);
        return pattern.getJSONArray("relationship") != null && !pattern.getJSONArray("relationship").isEmpty()
            || pattern.getJSONArray("relationships") != null && !pattern.getJSONArray("relationships").isEmpty()
            || pattern.getJSONArray("objects") != null && pattern.getJSONArray("objects").size() > 1;
    }

    OnnCoreGraphCompiler(JSONObject original, OnnCoreSchema schema, Map<String, Object> mapping) {
        this(original, schema, mapping, false);
    }
    OnnCoreGraphCompiler(JSONObject original, OnnCoreSchema schema, Map<String, Object> mapping, boolean extended) {
        this.schema = schema;
        this.extended = extended;
        JSONObject lowered = JSON.parseObject(JSON.toJSONString(original, com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue));
        JSONObject step = lowered.getJSONObject("answer").getJSONArray("steps").getJSONObject(0);
        JSONObject pattern = step.getJSONObject("graph").getJSONArray("patterns").getJSONObject(0);
        keys(pattern, "objects", "relationship", "relationships", "conditions", "idx");
        if (pattern.containsKey("relationship") && pattern.containsKey("relationships")) fail("ambiguous relationship keys");
        JSONArray links = pattern.getJSONArray(pattern.containsKey("relationship") ? "relationship" : "relationships");
        JSONArray defs = pattern.getJSONArray("objects");
        if (defs == null || defs.size() < 2 || defs.size() > 8 || links == null || links.size() != defs.size() - 1)
            fail("only connected relationship trees with 2..8 objects are supported");
        Set<String> seenClasses = new HashSet<>();
        for (Object raw : defs) {
            JSONObject object = JSONObject.from(raw);
            keys(object, "idx", "class", "variable", "conditions");
            int idx = index(object.get("idx"));
            String variable = object.getString("variable"), cls = object.getString("class");
            if (variable == null || !variable.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) fail("invalid graph variable");
            schema.requireClass(cls);
            if (objects.put(idx, object) != null || classes.put(variable, cls) != null) fail("duplicate graph object index or variable");
            // ONN SIMPLE excludes repeated class+id; ordinary DSL joins do not. Never silently lose such rows.
            if (!seenClasses.add(cls)) fail("repeated classes require walk semantics unavailable in ONN bindings");
        }
        List<Link> pending = new ArrayList<>();
        for (Object raw : links) {
            JSONObject link = JSONObject.from(raw);
            keys(link, "from", "to", "type", "min_hops", "max_hops");
            for (String hop : List.of("min_hops", "max_hops"))
                if (link.containsKey(hop) && index(link.get(hop)) != 1) fail("variable length relationships");
            int from = index(link.get("from")), to = index(link.get("to"));
            if (!objects.containsKey(from) || !objects.containsKey(to) || from == to) fail("unknown relationship endpoint");
            JSONArray types = link.getJSONArray("type");
            if (types == null || types.size() != 1 || !(types.get(0) instanceof String)) fail("relationship unions and wildcards");
            String type = types.getString(0);
            schema.validateRelationship(mapping, type, objects.get(from).getString("class"), objects.get(to).getString("class"));
            pending.add(new Link(from, to, type));
        }

        JSONArray steps = new JSONArray();
        int root = objects.keySet().iterator().next();
        JSONObject rootObject = objects.get(root);
        steps.add(JSONObject.of("name", rootObject.getString("variable"), "graph", nativeObject(rootObject, mapping)));
        Set<Integer> bound = new HashSet<>(Set.of(root));
        while (!pending.isEmpty()) {
            boolean progress = false;
            for (Iterator<Link> it = pending.iterator(); it.hasNext();) {
                Link link = it.next();
                boolean from = bound.contains(link.from), to = bound.contains(link.to);
                if (from == to) continue;
                JSONObject source = objects.get(from ? link.from : link.to), target = objects.get(from ? link.to : link.from);
                JSONObject graph = JSONObject.of("from_step", source.getString("variable"), "traverse", JSONObject.of(
                    "relation_path", link.type, "direction", from ? "out" : "in", "next", nativeObject(target, mapping)));
                steps.add(JSONObject.of("name", target.getString("variable"), "graph", graph));
                bound.add(from ? link.to : link.from);
                it.remove(); progress = true;
            }
            if (!progress) fail("disconnected or cyclic relationship pattern");
        }
        pipeline = JSONObject.of("steps", steps);
        if (pattern.getJSONObject("conditions") != null && !pattern.getJSONObject("conditions").isEmpty()) exactFilters = false;
        aggregatePlan = extended && exactFilters ? OnnCoreAggregatePlan.compile(original, schema, classes) : null;

        // Qualify every field into a collision-free logical binding column. All final filtering,
        // aggregates (including path multiplicity), grouping, sorting and windows remain local.
        JSONArray predicates = new JSONArray();
        for (JSONObject object : objects.values()) {
            JSONObject condition = object.getJSONObject("conditions");
            if (condition != null && !condition.isEmpty()) {
                keys(condition, "properties");
                JSONObject p = condition.getJSONObject("properties");
                if (p == null) fail("missing object predicate");
                rewrite(p, object.getString("variable"), 0);
                predicates.add(p);
            }
        }
        JSONObject condition = pattern.getJSONObject("conditions");
        if (condition != null && !condition.isEmpty()) {
            keys(condition, "properties");
            JSONObject p = condition.getJSONObject("properties");
            if (p == null) fail("missing pattern predicate");
            rewrite(p, null, 0); predicates.add(p);
        }
        rewrite(step.getJSONObject("output"), null, 0);
        JSONObject object = JSONObject.of("idx", 0, "class", TABLE, "variable", "row");
        if (!predicates.isEmpty()) object.put("conditions", JSONObject.of("properties", JSONObject.of("operator", "logic", "and", predicates)));
        pattern.remove("relationship"); pattern.remove("relationships"); pattern.remove("conditions");
        pattern.put("objects", JSONArray.of(object));
        JSONArray attributes = new JSONArray();
        columns.forEach((column, name) -> attributes.add(JSONObject.of("name", name, "dataType", schema.dataType(classes.get(column.variable), column.field))));
        OnnCoreSchema bindingSchema = new OnnCoreSchema(JSONObject.of("ontologies", JSONArray.of(JSONObject.of(
            "path", TABLE, "attributes", attributes)), "relations", new JSONArray()));
        residual = new OnnCoreDslCompiler(lowered, bindingSchema, bindingSchema.ontology());
        for (String variable : classes.keySet()) {
            Set<String> fields = new HashSet<>(Set.of("id"));
            columns.keySet().stream().filter(c -> c.variable.equals(variable)).forEach(c -> fields.add(c.field));
            schema.validateMapping(mapping, classes.get(variable), fields);
        }
    }

    private JSONObject nativeObject(JSONObject object, Map<String, Object> mapping) {
        JSONObject query = JSONObject.of("answer", JSONObject.of("steps", JSONArray.of(JSONObject.of(
            "graph", JSONObject.of("patterns", JSONArray.of(JSONObject.of("objects", JSONArray.of(object)))),
            "output", JSONObject.of("fields", JSONArray.of(JSONObject.of("variable", object.getString("variable"), "field", "id", "as", "id")))))));
        OnnCoreDslCompiler compiler = new OnnCoreDslCompiler(query, schema, mapping);
        exactFilters &= compiler.exactPredicates();
        JSONObject nativeQuery = compiler.nativeSnapshotRead(extended);
        nativeQuery.remove("output"); // Bindings hydrate complete objects; no user window is pushed ahead of residuals.
        return nativeQuery;
    }

    private void rewrite(Object value, String defaultVariable, int depth) {
        if (depth > 40) fail("binding expression depth");
        if (value instanceof JSONObject field) {
            if (field.containsKey("field")) {
                String variable = field.getString("variable");
                if (variable == null) variable = defaultVariable;
                if (variable == null || !classes.containsKey(variable)) fail("ambiguous or unknown binding variable");
                if (defaultVariable != null && !defaultVariable.equals(variable)) fail("foreign variable in object conditions");
                String name = field.getString("field");
                schema.sourceField(classes.get(variable), name);
                String column = columns.computeIfAbsent(new Column(variable, name), k -> "c" + columns.size());
                field.put("variable", "row"); field.put("field", column);
            }
            for (Object child : field.values()) if (child instanceof Map || child instanceof List) rewrite(child, defaultVariable, depth + 1);
        } else if (value instanceof List<?> list) for (Object child : list) rewrite(child, defaultVariable, depth + 1);
    }

    JSONObject nativePipeline() { return JSON.parseObject(pipeline.toJSONString()); }
    JSONObject projectedPipeline() {
        JSONObject query = nativePipeline();
        Map<String, Set<String>> projection = new LinkedHashMap<>();
        for (String variable : classes.keySet()) projection.put(variable, new TreeSet<>());
        for (Column column : columns.keySet()) {
            String source = schema.sourceField(classes.get(column.variable), column.field);
            if ("id".equals(source)) return query;
            if (source != null) projection.get(column.variable).add(source);
        }
        query.put("binding_output", projection);
        return query;
    }
    boolean nativeAggregate() { return aggregatePlan != null; }
    JSONObject aggregatePipeline() {
        JSONObject query = nativePipeline(); query.put("return", aggregatePlan.returns); return query;
    }
    JSONArray evaluateAggregate(OnnCoreSnapshotReader.Result result) { return aggregatePlan.evaluate(result); }
    Map<String, String> bindings() { return Collections.unmodifiableMap(classes); }

    JSONArray evaluate(List<JSONObject> bindings) {
        List<JSONObject> rows = new ArrayList<>();
        for (JSONObject binding : bindings) {
            identity(binding); // Also reject invalid optional/null rows on the mandatory-join contract.
            Map<String, JSONObject> flattened = new HashMap<>();
            classes.forEach((variable, cls) -> {
                JSONObject instance = new JSONObject(binding.getJSONObject(variable));
                instance.put("classPath", instance.getString("class_path"));
                flattened.put(variable, schema.flatten(cls, instance));
            });
            JSONObject row = JSONObject.of("id", identity(binding));
            columns.forEach((column, name) -> row.put(name, flattened.get(column.variable).get(column.field)));
            rows.add(row);
        }
        return residual.evaluate(rows);
    }

    String identity(JSONObject row) { return bindingIdentity(row, classes); }

    static String bindingIdentity(JSONObject row, Map<String, String> classes) {
        if (!row.keySet().equals(classes.keySet())) throw new IllegalStateException("ONN binding variables are missing or unexpected");
        List<Object> identities = new ArrayList<>();
        classes.forEach((variable, cls) -> {
            JSONObject instance = row.getJSONObject(variable);
            if (instance == null || !cls.equals(instance.getString("class_path")) || !(instance.get("id") instanceof String id)
                || id.isEmpty() || instance.getJSONObject("attributes") == null)
                throw new IllegalStateException("ONN binding has a missing or mismatched object identity");
            identities.add(List.of(variable, cls, id));
        });
        return JSON.toJSONString(identities);
    }

    private static int index(Object value) {
        try { return new java.math.BigDecimal(value.toString()).intValueExact(); }
        catch (RuntimeException error) { throw new UnsupportedOperationException("ONN graph requires integer object indices and hop bounds"); }
    }
    private static void keys(JSONObject object, String... allowed) {
        Set<String> permitted = Set.of(allowed);
        for (String key : object.keySet()) if (!permitted.contains(key)) fail("graph field " + key);
    }
    private static void fail(String message) { throw new UnsupportedOperationException("ONN Core adapter: " + message); }
}
