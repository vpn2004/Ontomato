package io.ontomato.dataengine.dataAdapter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter.Feature;
import java.util.*;

/** Compiles a bounded, request-local chain before issuing its first business read. */
final class OnnCoreQueryPlan {
    static final int MAX_STEPS = 4;
    // A sentinel row prevents the ordinary 5000-row output cap from becoming silent truncation.
    static final int MAX_INTERMEDIATE_ROWS = OnnCoreDslCompiler.MAX_ROWS - 1;
    static final String RULES = """
        ## Current data source: ONN Core execution constraints
        These source-specific constraints narrow the generic DSL rules/examples above.
        Prefer ONE step: relationship traversal, filters and count/sum/avg/min/max can share one graph.
        count with distinct:true already counts distinct non-null values; no temporary table is needed for it.
        For distinct detail rows, distinct:true on any non-aggregate output field deduplicates the ENTIRE
        projected tuple (including nulls), before offset/count. Order only by projected fields.
        Do not add identity columns to distinct output unless they are part of the requested uniqueness key.
        Supported multi-step shape: 2..4 linear steps in ONE DSL execution. Only the last step has to_user:true.
        Each earlier step has to_user:false and a unique save_table such as /temp01; do not limit intermediate
        steps. Each later graph reads exactly ONE object whose class is the immediately previous save_table,
        with fields named by the previous output aliases. Local filters, projection, distinct, grouping,
        having, supported aggregates, sorting and final pagination are supported. Temporary data is request-local.
        At most 4999 intermediate rows are allowed; exceeding this fails explicitly, never counts a partial page.
        Do not use expressions/expr, temporary-table joins, branches, multiple user outputs, multiple patterns,
        repeated classes, cycles, wildcard/union relationships or variable-length hops. Relationship graphs
        are connected trees of 2..8 distinct classes, with one named relationship per edge and exactly one hop.
        Output count is 0..5000, offset >= 0; an omitted FINAL limit defaults to 100 rows. Set a sufficient
        final count for requested complete details. Sort fields reference the input variable/field (and the
        same function/distinct for aggregate sorting), not the current output's as alias.
        For other computations use the tested Python post-calculation
        path and preserve source completeness/window evidence; never treat a preview or a limited page as all rows.
        If a shape is rejected, change the plan according to this contract instead of retrying the same shape.
        """;

    final JSONObject source;
    private final List<LocalStage> stages = new ArrayList<>();
    private record LocalStage(OnnCoreDslCompiler compiler, Map<String, String> columns, boolean intermediate) {
        JSONArray evaluate(JSONArray input) {
            List<JSONObject> rows = new ArrayList<>();
            for (int i = 0; i < input.size(); i++) {
                JSONObject original = input.getJSONObject(i);
                JSONObject row = JSONObject.of("id", String.format(Locale.ROOT, "%08d", i));
                columns.forEach((alias, column) -> row.put(column, original.get(alias)));
                rows.add(row);
            }
            JSONArray result = compiler.evaluate(rows);
            if (intermediate) requireBound(result);
            return result;
        }
    }

    static boolean isMultiStep(JSONObject dsl) {
        JSONObject answer = dsl.getJSONObject("answer");
        JSONArray steps = answer == null ? null : answer.getJSONArray("steps");
        return steps != null && steps.size() > 1;
    }

    OnnCoreQueryPlan(JSONObject original, OnnCoreSchema schema, Map<String, Object> mapping) {
        keys(original, "problem", "answer");
        JSONObject answer = required(original, "answer"); keys(answer, "steps");
        JSONArray steps = answer.getJSONArray("steps");
        if (steps == null || steps.size() < 2 || steps.size() > MAX_STEPS) fail("use 2..4 linear steps");
        Set<String> tables = new HashSet<>();
        Map<String, String> previousTypes = null;
        String previousTable = null;
        JSONObject first = null;
        for (int i = 0; i < steps.size(); i++) {
            boolean intermediate = i < steps.size() - 1;
            JSONObject step = copy(steps.getJSONObject(i));
            keys(step, "idx", "description", "graph", "output");
            JSONObject output = required(step, "output");
            String table = output.getString("save_table");
            if (intermediate) {
                if (!Boolean.FALSE.equals(output.get("to_user")) || table == null || !table.matches("/[A-Za-z].{0,62}")
                        || !tables.add(table)) fail("each intermediate needs to_user:false and a unique save_table");
                if (output.containsKey("limit")) fail("intermediate limits are unsupported; remove them to avoid partial aggregates");
                if (((List<?>) schema.ontology().get("classlist")).contains(table)) fail("temporary table shadows a business class");
            } else if (!Boolean.TRUE.equals(output.get("to_user")) || output.containsKey("save_table")) {
                fail("only the final step may return to_user:true, without save_table");
            }
            output.remove("save_table"); output.put("to_user", true);
            if (intermediate) {
                output.put("limit", JSONObject.of("count", OnnCoreDslCompiler.MAX_ROWS));
                // Force complete materialization rather than a source binding window.
                if (!output.containsKey("sort")) output.put("sort", JSONObject.of("fields", new JSONArray()));
            }
            JSONObject dsl = JSONObject.of("answer", JSONObject.of("steps", JSONArray.of(step)));
            Map<String, String> types;
            if (i == 0) {
                if (OnnCoreGraphCompiler.isGraph(dsl)) new OnnCoreGraphCompiler(dsl, schema, mapping);
                else new OnnCoreDslCompiler(dsl, schema, mapping);
                types = outputTypes(step, schema);
                first = dsl;
            } else {
                JSONObject graph = required(step, "graph"); keys(graph, "patterns", "pattern_logic");
                JSONArray patterns = graph.getJSONArray("patterns");
                if (patterns == null || patterns.size() != 1) fail("each later step must read one previous temporary table");
                JSONObject pattern = patterns.getJSONObject(0); keys(pattern, "objects", "relationships", "relationship", "conditions", "idx");
                for (String key : List.of("relationship", "relationships")) {
                    JSONArray links = pattern.getJSONArray(key);
                    if (links != null && !links.isEmpty()) fail("temporary-table joins are unsupported");
                    pattern.remove(key);
                }
                JSONArray objects = pattern.getJSONArray("objects");
                if (objects == null || objects.size() != 1) fail("temporary-table joins are unsupported");
                JSONObject object = objects.getJSONObject(0);
                if (!Objects.equals(previousTable, object.getString("class"))) fail("read only the immediately previous save_table");
                String variable = object.getString("variable");
                if (variable == null || variable.isBlank()) fail("missing temporary variable");
                Map<String, String> columns = new LinkedHashMap<>();
                JSONArray attrs = new JSONArray();
                for (var entry : previousTypes.entrySet()) {
                    String column = "c" + columns.size(); columns.put(entry.getKey(), column);
                    attrs.add(JSONObject.of("name", column, "dataType", entry.getValue()));
                }
                String internal = "/adapter/intermediate";
                OnnCoreSchema localSchema = new OnnCoreSchema(JSONObject.of("ontologies", JSONArray.of(
                    JSONObject.of("path", internal, "attributes", attrs)), "relations", new JSONArray()));
                object.put("class", internal);
                rewrite(step, variable, columns, 0);
                types = outputTypes(step, localSchema);
                stages.add(new LocalStage(new OnnCoreDslCompiler(dsl, localSchema, localSchema.ontology()), columns, intermediate));
            }
            previousTypes = types; previousTable = table;
        }
        source = first;
    }

    JSONArray evaluate(JSONArray input) {
        requireBound(input);
        JSONArray result = input;
        for (LocalStage stage : stages) result = stage.evaluate(result);
        return result;
    }

    private static Map<String, String> outputTypes(JSONObject step, OnnCoreSchema schema) {
        Map<String, String> classes = new HashMap<>();
        JSONArray objects = step.getJSONObject("graph").getJSONArray("patterns").getJSONObject(0).getJSONArray("objects");
        for (Object raw : objects) {
            JSONObject object = JSONObject.from(raw); classes.put(object.getString("variable"), object.getString("class"));
        }
        Map<String, String> types = new LinkedHashMap<>();
        for (Object raw : step.getJSONObject("output").getJSONArray("fields")) {
            JSONObject field = JSONObject.from(raw);
            String variable = field.getString("variable");
            if (variable == null && classes.size() == 1) variable = classes.keySet().iterator().next();
            String type = schema.dataType(classes.get(variable), field.getString("field"));
            String function = field.getString("function");
            if ("count".equals(function)) type = "int";
            else if ("avg".equals(function) || "sum".equals(function)) type = "float";
            types.put(field.getString("as"), type);
        }
        return types;
    }

    private static void rewrite(Object value, String variable, Map<String, String> columns, int depth) {
        if (depth > 40) fail("temporary expression depth");
        if (value instanceof JSONObject object) {
            if (object.containsKey("field")) {
                if (object.getString("variable") != null && !variable.equals(object.getString("variable"))) fail("foreign temporary variable");
                String column = columns.get(object.getString("field"));
                if (column == null) fail("unknown temporary field " + object.getString("field"));
                object.put("field", column); object.put("variable", variable);
            }
            for (var entry : object.entrySet()) {
                // Literal filter values are data, not field-reference AST nodes.
                if (!entry.getKey().equals("value")) rewrite(entry.getValue(), variable, columns, depth + 1);
            }
        } else if (value instanceof List<?> list) for (Object child : list) rewrite(child, variable, columns, depth + 1);
    }

    private static void requireBound(JSONArray rows) {
        if (rows.size() > MAX_INTERMEDIATE_ROWS) fail("intermediate exceeds 4999 rows; refusing a potentially truncated result");
    }
    private static JSONObject copy(JSONObject value) { return JSON.parseObject(JSON.toJSONString(value, Feature.WriteMapNullValue)); }
    private static JSONObject required(JSONObject value, String key) {
        JSONObject object = value.getJSONObject(key); if (object == null) fail("missing " + key); return object;
    }
    private static void keys(JSONObject object, String... allowed) {
        Set<String> keys = Set.of(allowed);
        for (String key : object.keySet()) if (!keys.contains(key)) fail("unsupported field " + key);
    }
    private static void fail(String detail) { throw new UnsupportedOperationException("ONN Core query plan: " + detail); }
}
