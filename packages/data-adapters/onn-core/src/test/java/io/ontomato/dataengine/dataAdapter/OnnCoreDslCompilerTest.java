package io.ontomato.dataengine.dataAdapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

class OnnCoreDslCompilerTest {
    private static final String CLASS = "/test/item";
    private final OnnCoreSchema schema = schema();

    @Test
    void rejectsUnsupportedKeysStructuresAndOperationsBeforeEvaluation() {
        Map<String, Consumer<JSONObject>> unsupported = new LinkedHashMap<>();
        unsupported.put("top-level extension", dsl -> dsl.put("native_query", new JSONObject()));
        unsupported.put("step extension", dsl -> step(dsl).put("from_step", "previous"));
        unsupported.put("graph extension", dsl -> graph(dsl).put("traverse", new JSONObject()));
        unsupported.put("object extension", dsl -> object(dsl).put("sandbox", "private"));
        unsupported.put("output extension", dsl -> output(dsl).put("native", true));
        unsupported.put("field extension", dsl -> output(dsl).getJSONArray("fields").getJSONObject(0).put("expression", "1"));
        unsupported.put("relations", dsl -> pattern(dsl).put("relationships", array(JSONObject.of("class", "/test/link"))));
        unsupported.put("multiple steps", dsl -> dsl.getJSONObject("answer").getJSONArray("steps").add(step(dsl)));
        unsupported.put("multiple objects", dsl -> pattern(dsl).getJSONArray("objects").add(object(dsl)));
        unsupported.put("multiple patterns", dsl -> graph(dsl).getJSONArray("patterns").add(pattern(dsl)));
        unsupported.put("foreign variable", dsl -> output(dsl).getJSONArray("fields").getJSONObject(0).put("variable", "other"));
        unsupported.put("unsupported function", dsl -> output(dsl).getJSONArray("fields").getJSONObject(0).put("function", "median"));
        unsupported.put("intermediate output", dsl -> output(dsl).put("to_user", false));
        unsupported.put("oversized page", dsl -> output(dsl).put("limit", JSONObject.of("count", 5001)));
        unsupported.put("negative offset", dsl -> output(dsl).put("limit", JSONObject.of("offset", -1)));
        for (Map.Entry<String, Consumer<JSONObject>> entry : unsupported.entrySet()) {
            JSONObject dsl = query(array(field("id", "id")), null);
            entry.getValue().accept(dsl);
            assertThrows(UnsupportedOperationException.class, () -> compile(dsl), entry.getKey());
        }
    }

    @Test
    void rowDistinctUsesTheWholeTypedProjectionBeforePagination() {
        JSONObject dsl = query(array(field("category", "category"), field("name", "value")), null);
        output(dsl).getJSONArray("fields").getJSONObject(0).put("distinct", true);
        List<JSONObject> rows = List.of(row("f", 0, "B", null), row("e", 0, "B", null),
            row("d", 0, "A", 1L), row("c", 0, "A", new BigDecimal("1.00")),
            row("b", 0, "A", "1"), row("a", 0, "B", "1"));
        JSONArray all = compile(dsl).evaluate(rows);
        assertEquals(4, all.size());
        output(dsl).put("limit", JSONObject.of("offset", 1, "count", 2));
        assertEquals(all.subList(1, 3), compile(dsl).evaluate(rows));
        List<JSONObject> reversed = new java.util.ArrayList<>(rows); java.util.Collections.reverse(reversed);
        assertEquals(all.subList(1, 3), compile(dsl).evaluate(reversed));
    }

    @Test
    void distinctRejectsAmbiguousOrderingAndMixedAggregates() {
        JSONObject dsl = query(array(field("category", "category")), null);
        output(dsl).getJSONArray("fields").getJSONObject(0).put("distinct", true);
        output(dsl).put("sort", JSONObject.of("fields", array(JSONObject.of("field", "id", "order", "asc"))));
        assertThrows(UnsupportedOperationException.class, () -> compile(dsl));
        output(dsl).remove("sort");
        output(dsl).getJSONArray("fields").add(aggregate("amount", "count", "count", false));
        assertThrows(UnsupportedOperationException.class, () -> compile(dsl));
    }

    @Test
    void rejectsUnknownClassesFieldsAndMalformedLogic() {
        JSONObject unknownClass = query(array(field("id", "id")), null);
        object(unknownClass).put("class", "/test/missing");
        assertThrows(IllegalArgumentException.class, () -> compile(unknownClass));
        assertThrows(IllegalArgumentException.class, () -> compile(query(array(field("missing", "value")), null)));
        assertThrows(UnsupportedOperationException.class, () -> compile(query(array(field("id", "id")),
            JSONObject.of("operator", "logic", "and", new JSONArray(), "or", new JSONArray()))));
        assertThrows(UnsupportedOperationException.class, () -> compile(query(array(field("id", "id")),
            JSONObject.of("operator", "logic", "and", new JSONArray()))));
        JSONObject unknownPredicateKey = predicate("amount", "=", 1);
        unknownPredicateKey.put("where_logic", "or");
        assertThrows(UnsupportedOperationException.class, () -> compile(query(array(field("id", "id")), unknownPredicateKey)));
    }

    @Test
    void implementsNullAndInPredicatesWithSqlThreeValuedLogic() {
        List<JSONObject> rows = List.of(row("a", 1, "A", "one"), row("b", 2, "A", "two"), row("c", null, "B", null));
        assertEquals(List.of(), ids(predicate("amount", "=", null), rows));
        assertEquals(List.of(), ids(predicate("amount", "!=", null), rows));
        assertEquals(List.of("a"), ids(predicate("amount", "in", Arrays.asList(1, null)), rows));
        assertEquals(List.of(), ids(predicate("amount", "not in", Arrays.asList(1, null)), rows));
        assertEquals(List.of("b"), ids(predicate("amount", "not in", List.of(1)), rows));
        assertEquals(List.of("c"), ids(predicate("amount", "is", null), rows));
        assertEquals(List.of("a", "b"), ids(predicate("amount", "is not", null), rows));
        assertEquals(List.of("a"), ids(logic("or", predicate("amount", "=", 1), predicate("amount", "=", null)), rows));
        assertEquals(List.of(), ids(logic("and", predicate("amount", "=", 1), predicate("amount", "=", null)), rows));
    }

    @Test
    void countDistinctKeepsTypedValuesAndNormalizesEquivalentNumbers() {
        JSONObject dsl = query(array(aggregate("name", "count", "distinct_count", true),
            aggregate("name", "count", "non_null_count", false)), null);
        List<JSONObject> rows = List.of(row("a", 0, "A", 1), row("b", 0, "A", 1L),
            row("c", 0, "A", new BigDecimal("1.00")), row("d", 0, "A", "1"),
            row("e", 0, "A", true), row("f", 0, "A", "true"), row("g", 0, "A", null));
        JSONObject result = compile(dsl).evaluate(rows).getJSONObject(0);
        assertEquals(4L, result.getLongValue("distinct_count"));
        assertEquals(6L, result.getLongValue("non_null_count"));
    }

    @Test
    void appliesGroupingHavingAggregatesSortingAndPaginationInOrder() {
        JSONObject dsl = query(array(field("category", "category"), aggregate("amount", "sum", "total", false),
            aggregate("amount", "avg", "average", false), aggregate("amount", "count", "count", false)), null);
        JSONObject having = aggregate("amount", "sum", null, false);
        having.remove("as"); having.put("operator", ">="); having.put("value", 20);
        output(dsl).put("group_by", JSONObject.of("fields", array(groupField("category")), "having", array(having)));
        JSONObject sort = aggregate("amount", "sum", null, false);
        sort.remove("as"); sort.put("order", "desc");
        output(dsl).put("sort", JSONObject.of("fields", array(sort)));
        output(dsl).put("limit", JSONObject.of("count", 1, "offset", 1));
        JSONArray answer = compile(dsl).evaluate(List.of(row("a", 10, "A", "a"), row("b", 20L, "A", "b"),
            row("c", 8, "B", "c"), row("d", 10, "B", "d"), row("e", 50, "C", "e")));
        assertEquals(1, answer.size());
        JSONObject result = answer.getJSONObject(0);
        assertEquals("A", result.getString("category"));
        assertDecimal("30", result.get("total"));
        assertDecimal("15", result.get("average"));
        assertEquals(2L, result.getLongValue("count"));
    }

    @Test
    void emptyUngroupedAggregatesHaveSqlNullAndCountSemantics() {
        JSONArray answer = compile(query(array(aggregate("amount", "sum", "sum", false),
            aggregate("amount", "avg", "avg", false), aggregate("amount", "count", "count", false)), null)).evaluate(List.of());
        assertEquals(1, answer.size());
        assertNull(answer.getJSONObject(0).get("sum"));
        assertNull(answer.getJSONObject(0).get("avg"));
        assertEquals(0L, answer.getJSONObject(0).getLongValue("count"));
    }

    @Test
    void groupingPageTieBreakDoesNotDependOnInputOrder() {
        JSONObject dsl = query(array(field("category", "category"), aggregate("amount", "sum", "total", false)), null);
        output(dsl).put("group_by", JSONObject.of("fields", array(groupField("category"))));
        JSONObject sort = groupField("amount"); sort.put("function", "sum"); sort.put("order", "desc");
        output(dsl).put("sort", JSONObject.of("fields", array(sort)));
        output(dsl).put("limit", JSONObject.of("count", 1, "offset", 0));
        JSONObject high = row("z", 1, "A", "high"), low = row("a", null, "A", "low"), middle = row("m", 1, "B", "middle");
        OnnCoreDslCompiler compiler = compile(dsl);
        JSONArray first = compiler.evaluate(List.of(high, middle, low));
        JSONArray second = compiler.evaluate(List.of(low, middle, high));
        assertEquals(first, second, "Equal aggregate sort keys must select the same group on every page");
        assertEquals(1, first.size());
    }

    @Test
    void sortingObjectRowsUsesIdentityForTiesBeforePagination() {
        JSONObject dsl = query(array(field("id", "id")), null);
        JSONObject sort = groupField("amount"); sort.put("order", "desc");
        output(dsl).put("sort", JSONObject.of("fields", array(sort)));
        output(dsl).put("limit", JSONObject.of("count", 2, "offset", 1));
        JSONArray answer = compile(dsl).evaluate(List.of(row("c", 1, "A", "c"), row("b", 3, "A", "b"),
            row("a", 3, "A", "a"), row("d", null, "A", "d")));
        assertEquals(List.of("a", "b"), answer.stream().map(JSONObject::from).map(r -> r.getString("id")).toList(),
            "DESC orders nulls first, then tied values by ascending identity before slicing");
    }

    @Test
    void identitySelectionPreservesColonsAndDoesNotNarrowAnOrPredicate() {
        String id = "asset:zone:42";
        OnnCoreDslCompiler direct = compile(query(array(field("id", "id")), predicate("id", "=", id)));
        assertEquals(List.of(id), direct.identitySelection());
        assertEquals(id, direct.evaluate(List.of(row(id, 1, "A", "one"))).getJSONObject(0).getString("id"));
        OnnCoreDslCompiler disjunction = compile(query(array(field("id", "id")),
            logic("or", predicate("id", "=", id), predicate("amount", ">", 0))));
        assertNull(disjunction.identitySelection());
        assertEquals(2, disjunction.evaluate(List.of(row(id, 1, "A", "one"), row("other", 1, "B", "two"))).size());
    }

    @Test
    void datetimePredicatesCompareInstantsAfterConvertingExpectedValues() {
        JSONObject dsl = query(array(field("id", "id")), predicate("when", "<", "2026-10-09T10:00:00+08:00"));
        JSONObject early = flattenedDate("early", "2026-10-09T09:00:00+08:00");
        JSONObject late = flattenedDate("late", Instant.parse("2026-10-09T03:00:00Z").getEpochSecond());
        assertEquals(List.of("early"), compile(dsl).evaluate(List.of(late, early)).stream()
            .map(JSONObject::from).map(r -> r.getString("id")).toList());
        JSONObject in = query(array(field("id", "id")), predicate("when", "in", List.of("2026-10-09T01:00:00Z")));
        assertEquals("early", compile(in).evaluate(List.of(early, late)).getJSONObject(0).getString("id"));
    }

    @Test
    void datetimeSortingUsesChronologyRatherThanWireStringOrdering() {
        JSONObject dsl = query(array(field("id", "id")), null);
        JSONObject sort = groupField("when"); sort.put("order", "asc");
        output(dsl).put("sort", JSONObject.of("fields", array(sort)));
        JSONObject early = flattenedDate("early", "2026-10-09T09:00:00+08:00");
        JSONObject late = flattenedDate("late", "2026-10-09T03:00:00Z");
        assertEquals(List.of("early", "late"), compile(dsl).evaluate(List.of(late, early)).stream()
            .map(JSONObject::from).map(r -> r.getString("id")).toList());
    }

    @Test
    void datetimeGroupingCombinesEquivalentInstantsFromDifferentWireFormats() {
        JSONObject dsl = query(array(field("when", "when"), aggregate("id", "count", "count", false)), null);
        output(dsl).put("group_by", JSONObject.of("fields", array(groupField("when"))));
        JSONArray answer = compile(dsl).evaluate(List.of(
            flattenedDate("offset", "2026-10-09T08:00:00+08:00"), flattenedDate("utc", "2026-10-09T00:00:00Z")));
        assertEquals(1, answer.size());
        assertEquals(2L, answer.getJSONObject(0).getLongValue("count"));
    }

    @Test
    void rejectsNumericAggregatesOnTextAndLikeOnNumericFields() {
        assertThrows(UnsupportedOperationException.class, () -> compile(query(array(aggregate("name", "sum", "sum", false)), null)));
        assertThrows(UnsupportedOperationException.class, () -> compile(query(array(aggregate("name", "avg", "avg", false)), null)));
        assertThrows(UnsupportedOperationException.class, () -> compile(query(array(field("id", "id")), predicate("amount", "like", "%"))));
    }

    @Test
    void nativeReadKeepsBusinessPredicatesAndTraversalOutOfTheFetch() {
        JSONObject read = compile(query(array(field("id", "id")), predicate("amount", ">", 10))).nativeRead();
        assertEquals(CLASS, read.getString("match_class"));
        assertEquals(5001, read.getIntValue("limit"));
        assertTrue(List.of("where", "where_logic", "traverse", "traverses", "output").stream().noneMatch(read::containsKey));
    }

    private OnnCoreDslCompiler compile(JSONObject dsl) { return new OnnCoreDslCompiler(dsl, schema, schema.ontology()); }

    @Test void pushesSafeConjunctionsButNeverDropsAnUnconstrainedOrBranch() {
        JSONObject safe = predicate("category", "in", List.of("A", "B"));
        JSONObject unsafe = predicate("when", "=", "2026-01-15T00:00:00Z");
        assertTrue(compile(query(array(field("id", "id")), logic("and", safe, unsafe))).nativeSnapshotRead().containsKey("where_logic"));
        assertTrue(!compile(query(array(field("id", "id")), logic("or", safe, unsafe))).nativeSnapshotRead().containsKey("where_logic"));
        JSONObject safeOr = compile(query(array(field("id", "id")), logic("or", safe, predicate("name", "=", "one")))).nativeSnapshotRead();
        assertEquals("or", safeOr.getJSONObject("where_logic").getJSONArray("children").getJSONObject(0).getString("op"));
        for (JSONObject residual : List.of(predicate("amount", "=", 1), predicate("amount", ">", 1),
            predicate("name", "!=", "1"), predicate("name", "is", null), predicate("name", "=", null),
            predicate("name", "in", Arrays.asList("one", null)), predicate("when", "in", List.of("2026-01-15")))) {
            assertTrue(!compile(query(array(field("id", "id")), residual)).nativeSnapshotRead().containsKey("where_logic"), residual.toString());
        }
    }

    @Test void projectionIncludesFilterGroupingHavingAndSortingDependencies() {
        JSONObject dsl = query(array(field("category", "category"), aggregate("amount", "sum", "total", false)), predicate("name", "=", "one"));
        output(dsl).put("group_by", JSONObject.of("fields", array(groupField("category"))));
        JSONObject read = compile(dsl).nativeSnapshotRead();
        assertEquals(List.of("amount", "category", "name"), read.getJSONObject("output").getJSONArray("fields")
            .stream().map(JSONObject::from).map(f -> f.getString("attribute")).toList());
        assertTrue(!read.containsKey("limit"));
        assertTrue(!read.containsKey("metrics"));
    }

    @Test void exactCountRequiresAnUnfilteredUngroupedIdentityAggregateAndHonorsWindows() {
        JSONObject dsl = query(array(aggregate("id", "count", "total", true)), null);
        assertTrue(compile(dsl).nativeIdentityCount());
        assertEquals(6001L, compile(dsl).evaluateIdentityCount(6001).getJSONObject(0).getLongValue("total"));
        output(dsl).put("limit", JSONObject.of("count", 0));
        assertTrue(compile(dsl).evaluateIdentityCount(6001).isEmpty());
        output(dsl).put("limit", JSONObject.of("count", 1, "offset", 1));
        assertTrue(compile(dsl).evaluateIdentityCount(6001).isEmpty());
        assertTrue(!compile(query(array(aggregate("amount", "count", "total", false)), null)).nativeIdentityCount());
        assertTrue(!compile(query(array(aggregate("id", "count", "total", false)), predicate("name", "=", "one"))).nativeIdentityCount());
    }
    private List<String> ids(JSONObject predicate, List<JSONObject> rows) {
        return compile(query(array(field("id", "id")), predicate)).evaluate(rows).stream()
            .map(JSONObject::from).map(r -> r.getString("id")).toList();
    }
    private JSONObject flattenedDate(String id, Object date) {
        return schema.flatten(CLASS, JSONObject.of("id", id, "classPath", CLASS, "attributes", JSONObject.of("when", date)));
    }
    private static OnnCoreSchema schema() {
        return new OnnCoreSchema(JSONObject.of("ontologies", array(JSONObject.of("path", CLASS, "name", "Item", "attributes", array(
            JSONObject.of("name", "amount", "dataType", "float"), JSONObject.of("name", "category", "dataType", "string"),
            JSONObject.of("name", "name", "dataType", "string"), JSONObject.of("name", "when", "dataType", "datetime")))),
            "relations", new JSONArray()));
    }
    private static JSONObject query(JSONArray fields, JSONObject predicate) {
        JSONObject object = JSONObject.of("class", CLASS, "variable", "o");
        if (predicate != null) object.put("conditions", JSONObject.of("properties", predicate));
        return JSONObject.of("answer", JSONObject.of("steps", array(JSONObject.of("graph", JSONObject.of("patterns",
            array(JSONObject.of("objects", array(object), "relationships", new JSONArray()))),
            "output", JSONObject.of("fields", fields, "to_user", true)))));
    }
    private static JSONObject field(String name, String alias) { return JSONObject.of("variable", "o", "field", name, "as", alias); }
    private static JSONObject groupField(String name) { return JSONObject.of("variable", "o", "field", name); }
    private static JSONObject aggregate(String name, String function, String alias, boolean distinct) {
        JSONObject field = field(name, alias); field.put("function", function); field.put("distinct", distinct); return field;
    }
    private static JSONObject predicate(String name, String operator, Object value) { return JSONObject.of("field", name, "operator", operator, "value", value); }
    private static JSONObject logic(String operator, JSONObject... children) { return JSONObject.of("operator", "logic", operator, array((Object[]) children)); }
    private static JSONObject row(String id, Object amount, String category, Object name) { return JSONObject.of("id", id, "amount", amount, "category", category, "name", name); }
    private static JSONObject step(JSONObject dsl) { return dsl.getJSONObject("answer").getJSONArray("steps").getJSONObject(0); }
    private static JSONObject graph(JSONObject dsl) { return step(dsl).getJSONObject("graph"); }
    private static JSONObject pattern(JSONObject dsl) { return graph(dsl).getJSONArray("patterns").getJSONObject(0); }
    private static JSONObject object(JSONObject dsl) { return pattern(dsl).getJSONArray("objects").getJSONObject(0); }
    private static JSONObject output(JSONObject dsl) { return step(dsl).getJSONObject("output"); }
    private static JSONArray array(Object... values) { JSONArray result = new JSONArray(); result.addAll(Arrays.asList(values)); return result; }
    private static void assertDecimal(String expected, Object actual) { assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.toString()))); }
}
