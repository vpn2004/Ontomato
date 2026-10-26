package io.ontomato.dataengine.dataAdapter;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;
import java.util.regex.Pattern;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

/**
 * Compile a deliberately bounded single-class DSL. Filters/metrics are evaluated over
 * the complete fetched class, so leaf-node counts, float coercion and ABC translation
 * cannot silently change Ontomato semantics. Unsupported structures fail before reads.
 */
final class OnnCoreDslCompiler {
    static final int MAX_ROWS = 5000;
    final String className;
    private final String variable;
    private final List<JSONObject> predicates = new ArrayList<>();
    private final JSONArray fields, groups, having, sort;
    private final boolean aggregate;
    private final int count, offset;
    private final Set<String> referenced = new HashSet<>();
    private final OnnCoreSchema schema;

    OnnCoreDslCompiler(JSONObject dsl, OnnCoreSchema schema, Map<String, Object> mapping) {
        this.schema = schema;
        keys(dsl, "problem", "answer");
        JSONObject answer = required(dsl, "answer"); keys(answer, "steps");
        JSONArray steps = one(answer, "steps"); JSONObject step = steps.getJSONObject(0);
        keys(step, "graph", "output", "idx", "description");
        JSONObject graph = required(step, "graph"); keys(graph, "patterns", "pattern_logic");
        if (graph.getString("pattern_logic") != null && !Set.of("and", "or").contains(graph.getString("pattern_logic"))) fail("pattern_logic");
        JSONObject pattern = one(graph, "patterns").getJSONObject(0);
        keys(pattern, "objects", "relationships", "conditions", "idx");
        JSONArray relationships = pattern.getJSONArray("relationships");
        if (relationships != null && !relationships.isEmpty()) fail("relationship bindings and graph traversal");
        JSONObject object = one(pattern, "objects").getJSONObject(0);
        keys(object, "class", "variable", "idx", "conditions");
        className = text(object, "class"); variable = text(object, "variable");
        schema.requireClass(className);
        conditions(object.getJSONObject("conditions"));
        conditions(pattern.getJSONObject("conditions"));
        JSONObject output = required(step, "output");
        keys(output, "fields", "to_user", "sort", "limit", "group_by");
        if (Boolean.FALSE.equals(output.getBoolean("to_user"))) fail("intermediate output");
        fields = output.getJSONArray("fields");
        if (fields == null || fields.isEmpty()) fail("empty output fields");
        Set<String> aliases = new HashSet<>(); boolean anyAggregate = false;
        for (int i=0;i<fields.size();i++) {
            JSONObject f=fields.getJSONObject(i); field(f, true);
            if (!aliases.add(text(f,"as"))) fail("duplicate output alias");
            if (hasFunction(f)) anyAggregate=true;
            else if (Boolean.TRUE.equals(f.getBoolean("distinct"))) fail("row DISTINCT");
        }
        aggregate=anyAggregate;
        JSONObject grouping=output.getJSONObject("group_by");
        if (grouping != null) keys(grouping, "fields", "having");
        groups=grouping==null || grouping.getJSONArray("fields")==null ? new JSONArray() : grouping.getJSONArray("fields");
        having=grouping==null || grouping.getJSONArray("having")==null ? new JSONArray() : grouping.getJSONArray("having");
        Set<String> groupFields=new HashSet<>();
        for (Object raw:groups) { JSONObject f=JSONObject.from(raw); field(f,false); if (hasFunction(f)) fail("group key function"); groupFields.add(f.getString("field")); }
        if (!aggregate && (!groups.isEmpty() || !having.isEmpty())) fail("grouping without aggregates");
        for (Object raw:fields) { JSONObject f=JSONObject.from(raw); if (aggregate && !hasFunction(f) && !groupFields.contains(f.getString("field"))) fail("non-grouped output field"); }
        for (Object raw:having) { JSONObject f=JSONObject.from(raw); keys(f,"variable","field","function","distinct","operator","value"); validateField(f); validateFunction(f); if (!hasFunction(f)) fail("HAVING without aggregate"); validateOperator(f); }
        JSONObject sorting=output.getJSONObject("sort"); if (sorting!=null) keys(sorting,"fields");
        sort=sorting==null || sorting.getJSONArray("fields")==null ? new JSONArray() : sorting.getJSONArray("fields");
        for (Object raw:sort) {
            JSONObject f=JSONObject.from(raw); keys(f,"variable","field","order","function","distinct"); validateField(f); validateFunction(f);
            if (!Set.of("asc","desc").contains(Objects.toString(f.getString("order"), "asc"))) fail("sort order");
            if (aggregate && !hasFunction(f) && !groupFields.contains(f.getString("field"))) fail("aggregate sorting by a non-grouped field");
            if (!aggregate && hasFunction(f)) fail("aggregate sort on object rows");
        }
        JSONObject limit=output.getJSONObject("limit"); if(limit!=null) keys(limit,"count","offset");
        count=limit==null ? 100 : integer(limit,"count",100); offset=limit==null ? 0 : integer(limit,"offset",0);
        if(count<0 || count>MAX_ROWS || offset<0) fail("pagination bounds (count must be 0..5000, offset >= 0)");
        schema.validateMapping(mapping,className,referenced);
    }

    JSONObject nativeRead() { return JSONObject.of("match_class",className,"limit",MAX_ROWS+1,"metrics",List.of(JSONObject.of("name","total","type","count"))); }

    /** Only a conjunctive virtual-id filter can narrow the fetch without changing OR semantics. */
    List<String> identitySelection() {
        for (JSONObject p:predicates) { List<String> ids=idsInAnd(p); if(ids!=null) return ids; }
        return null;
    }
    private List<String> idsInAnd(JSONObject p) {
        if ("logic".equals(p.getString("operator"))) {
            JSONArray and=p.getJSONArray("and"); if (and==null) return null;
            for(Object raw:and) { List<String> ids=idsInAnd(JSONObject.from(raw)); if(ids!=null) return ids; }
            return null;
        }
        if (!"id".equals(p.getString("field"))) return null;
        Object value=p.get("value");
        if("=".equals(p.getString("operator")) && value instanceof String s) return List.of(s);
        if("in".equals(p.getString("operator")) && value instanceof List<?> list && list.size()<=100 && list.stream().allMatch(String.class::isInstance)) return list.stream().map(String.class::cast).distinct().toList();
        return null;
    }

    JSONArray evaluate(List<JSONObject> input) {
        List<JSONObject> matched=input.stream().filter(row -> predicates.stream().allMatch(p -> Boolean.TRUE.equals(test(p,row)))).toList();
        List<List<JSONObject>> buckets=new ArrayList<>();
        if(aggregate) {
            if(groups.isEmpty()) buckets.add(matched);
            else {
                Map<List<Object>,List<JSONObject>> grouped=new LinkedHashMap<>();
                for(JSONObject row:matched) { List<Object> key=groups.stream().map(JSONObject::from).map(f->typed(f,row.get(f.getString("field")))).toList(); grouped.computeIfAbsent(key,k->new ArrayList<>()).add(row); }
                buckets.addAll(grouped.values());
            }
        } else for(JSONObject row:matched) buckets.add(List.of(row));
        buckets.removeIf(bucket -> having.stream().map(JSONObject::from).anyMatch(f -> !Boolean.TRUE.equals(compareCondition(f,value(f,bucket)))));
        Comparator<List<JSONObject>> comparator=(a,b)->0;
        for(Object raw:sort) {
            JSONObject f=JSONObject.from(raw); boolean desc="desc".equals(f.getString("order"));
            comparator=comparator.thenComparing((a,b)->compareNullable(typed(f,value(f,a)),typed(f,value(f,b)),desc));
        }
        // An explicit identity tie-breaker makes pages deterministic over the complete set.
        comparator=comparator.thenComparing(bucket -> bucket.stream().map(row->row.getString("id")).min(String::compareTo).orElse(""));
        buckets.sort(comparator);
        JSONArray result=new JSONArray();
        for(int i=Math.min(offset,buckets.size());i<buckets.size() && result.size()<count;i++) {
            JSONObject row=new JSONObject(); for(Object raw:fields) { JSONObject f=JSONObject.from(raw); row.put(f.getString("as"),value(f,buckets.get(i))); } result.add(row);
        }
        return result;
    }

    private void conditions(JSONObject c) {
        if(c==null || c.isEmpty()) return;
        keys(c,"properties"); JSONObject p=c.getJSONObject("properties");
        if(p==null) fail("missing properties predicate");
        validatePredicate(p,0); predicates.add(p);
    }
    private void validatePredicate(JSONObject p,int depth) {
        if(depth>32) fail("predicate depth");
        if("logic".equals(p.getString("operator"))) {
            keys(p,"operator","and","or");
            if(p.containsKey("and")==p.containsKey("or")) fail("logic must contain exactly one of and/or");
            JSONArray children=p.getJSONArray(p.containsKey("and")?"and":"or"); if(children==null || children.isEmpty()) fail("empty logic predicate");
            for(Object raw:children) validatePredicate(JSONObject.from(raw),depth+1);
        } else { keys(p,"field","variable","operator","value"); validateField(p); validateOperator(p); }
    }
    private void field(JSONObject f,boolean alias) {
        if(alias) keys(f,"variable","field","as","function","distinct");
        else keys(f,"variable","field","function","distinct");
        validateField(f); validateFunction(f);
    }
    private void validateField(JSONObject f) {
        if(f.getString("variable")!=null && !variable.equals(f.getString("variable"))) fail("foreign variable " + f.getString("variable"));
        String name=text(f,"field"); schema.sourceField(className,name); referenced.add(name);
    }
    private void validateFunction(JSONObject f) {
        if(hasFunction(f) && !Set.of("count","sum","avg","min","max").contains(f.getString("function"))) fail("aggregate function " + f.getString("function"));
        if(Set.of("sum","avg").contains(Objects.toString(f.getString("function"),""))
            && !Set.of("int","float").contains(schema.dataType(className,f.getString("field")))) fail("SUM/AVG require numeric fields");
    }
    private static boolean hasFunction(JSONObject f) { return f.getString("function")!=null && !f.getString("function").isBlank(); }
    private void validateOperator(JSONObject p) {
        String op=text(p,"operator");
        if(!Set.of("=","!=","<>",">",">=","<","<=","in","not in","between","is","is not","like","not like").contains(op)) fail("operator " + op);
        Object v=p.get("value");
        if(!p.containsKey("value")) fail("missing condition value");
        if(Set.of("in","not in","between").contains(op)) {
            if(!(v instanceof List<?> list) || (op.equals("between") && list.size()!=2)) fail("condition requires an array");
        } else if(v instanceof Map<?,?> || v instanceof List<?>) fail("non-scalar condition");
        if(Set.of("is","is not").contains(op) && v!=null) fail("IS predicate only supports null");
        if(Set.of("like","not like").contains(op) && !"string".equals(schema.dataType(className,p.getString("field")))) fail("LIKE requires a string field");
        if(v instanceof List<?> values) for(Object item:values) typed(p,item);
        else typed(p,v);
    }
    private Boolean test(JSONObject p,JSONObject row) {
        if("logic".equals(p.getString("operator"))) {
            boolean and=p.containsKey("and"), unknown=false;
            for(Object raw:p.getJSONArray(and?"and":"or")) {
                Boolean child=test(JSONObject.from(raw),row);
                if(child==null) unknown=true;
                else if(and && !child) return false;
                else if(!and && child) return true;
            }
            return unknown?null:and;
        }
        return compareCondition(p,row.get(p.getString("field")));
    }
    private Boolean compareCondition(JSONObject p,Object actual) {
        String op=p.getString("operator"); Object expected=p.get("value");
        if(op.equals("is")) return actual==null;
        if(op.equals("is not")) return actual!=null;
        if(actual==null) return null;
        actual=typed(p,actual);
        if(op.equals("in") || op.equals("not in")) {
            boolean unknown=false; for(Object v:(List<?>)expected) { if(v==null) unknown=true; else if(compare(actual,typed(p,v))==0) return op.equals("in"); }
            return unknown?null:op.equals("not in");
        }
        if(op.equals("between")) { List<?> bounds=(List<?>)expected; if(bounds.get(0)==null || bounds.get(1)==null) return null; return compare(actual,typed(p,bounds.get(0)))>=0 && compare(actual,typed(p,bounds.get(1)))<=0; }
        if(expected==null) return null;
        expected=typed(p,expected);
        if(op.equals("like") || op.equals("not like")) {
            if(!(actual instanceof String a) || !(expected instanceof String b)) fail("LIKE requires strings");
            StringBuilder regex=new StringBuilder("(?s)");
            for(char ch:((String)expected).toCharArray()) { if(ch=='%') regex.append(".*"); else if(ch=='_') regex.append('.'); else if(ch=='\\') fail("LIKE escapes"); else regex.append(Pattern.quote(String.valueOf(ch))); }
            boolean match=((String)actual).matches(regex.toString()); return op.equals("like")==match;
        }
        int cmp=compare(actual,expected);
        return switch(op) { case "="->cmp==0; case "!=","<>"->cmp!=0; case ">"->cmp>0; case ">="->cmp>=0; case "<"->cmp<0; case "<="->cmp<=0; default->throw new IllegalStateException("Unvalidated operator"); };
    }
    private Object value(JSONObject f,List<JSONObject> bucket) {
        String name=f.getString("field");
        if(!hasFunction(f)) return bucket.isEmpty()?null:bucket.get(0).get(name);
        List<Object> values=bucket.stream().map(r->r.get(name)).filter(Objects::nonNull)
            .map(v->normalize(schema.temporalValue(className,name,v))).toList();
        if(Boolean.TRUE.equals(f.getBoolean("distinct"))) values=values.stream().distinct().toList();
        if("count".equals(f.getString("function"))) return (long)values.size();
        if(values.isEmpty()) return null;
        return switch(f.getString("function")) {
            case "min" -> values.stream().min(OnnCoreDslCompiler::compare).orElseThrow();
            case "max" -> values.stream().max(OnnCoreDslCompiler::compare).orElseThrow();
            case "sum","avg" -> {
                BigDecimal sum=BigDecimal.ZERO; for(Object v:values) { if(!(v instanceof BigDecimal)) fail("SUM/AVG require numeric data"); sum=sum.add((BigDecimal)v); }
                yield "avg".equals(f.getString("function")) ? sum.divide(BigDecimal.valueOf(values.size()),MathContext.DECIMAL128) : sum;
            }
            default -> throw new IllegalStateException("Unvalidated function");
        };
    }
    private Object typed(JSONObject field,Object value) {
        String fn=field.getString("function");
        if(fn!=null && !fn.isBlank() && !Set.of("min","max").contains(fn)) return normalize(value);
        return normalize(schema.temporalValue(className,field.getString("field"),value));
    }
    private static Object normalize(Object v) { return v instanceof Number ? new BigDecimal(v.toString()).stripTrailingZeros() : v; }
    @SuppressWarnings({"unchecked","rawtypes"})
    private static int compare(Object a,Object b) {
        a=normalize(a); b=normalize(b);
        if(!a.getClass().equals(b.getClass()) || !(a instanceof Comparable)) fail("incompatible value types; implicit coercion is unsupported");
        return ((Comparable)a).compareTo(b);
    }
    private static int compareNullable(Object a,Object b,boolean desc) {
        if(a==null) return b==null?0:(desc?-1:1);
        if(b==null) return desc?1:-1;
        int c=compare(a,b); return desc?-c:c;
    }
    private static int integer(JSONObject o,String field,int fallback) {
        Object raw=o.get(field); if(raw==null) return fallback;
        try { return new BigDecimal(raw.toString()).intValueExact(); } catch(Exception e) { throw new IllegalArgumentException("Invalid integer: " + field); }
    }
    private static JSONObject required(JSONObject o,String key) { JSONObject v=o.getJSONObject(key); if(v==null) throw new IllegalArgumentException("Missing DSL " + key); return v; }
    private static JSONArray one(JSONObject o,String key) { JSONArray a=o.getJSONArray(key); if(a==null || a.size()!=1) fail("exactly one " + key); return a; }
    private static String text(JSONObject o,String key) { String v=o.getString(key); if(v==null || v.isBlank()) throw new IllegalArgumentException("Missing DSL " + key); return v; }
    private static void keys(JSONObject o,String... allowed) { Set<String> keys=Set.of(allowed); for(String key:o.keySet()) if(!keys.contains(key)) fail("DSL field " + key); }
    private static void fail(String feature) { throw new UnsupportedOperationException("ONN Core adapter does not support " + feature); }
}
