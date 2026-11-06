package io.ontomato.dataengine.dataAdapter;

import java.util.*;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter.Feature;
import io.ontomato.dataengine.config.DataRagConfig;
import io.ontomato.dataengine.dao.VectorResourceDao;
import io.ontomato.dataengine.service.sys.bean.permission.UserDataPermission;
import io.ontomato.dataengine.util.DslUtil;
import io.ontomato.dataengine.util.HttpRequestUtil;
import io.ontomato.dataengine.dataAdapter.DslExecutionMode;
import io.ontomato.dataengine.bean.DslExecutionResult;
import java.sql.Connection;
import java.sql.SQLException;

/** ONN carries business objects; platform persistence and attachment indices remain PostgreSQL. */
public final class OnnCoreAdapter implements DataAdapter, ConditionalObjectWrites {
    private final OnnCoreHttpClient client;
    private final Map<String, Object> mapping;
    private OnnCoreSchema sampleSchema;

    public OnnCoreAdapter(DataAdapterConnection connection, Map<String, Object> mapping) {
        this(connection, mapping, null);
    }
    public OnnCoreAdapter(DataAdapterConnection connection, Map<String, Object> mapping, Principal readPrincipal) {
        var transport = new OnnCoreHttpClient(connection.getUrl(), connection.getToken());
        this.client = readPrincipal == null ? transport : transport.asPrincipal(readPrincipal); this.mapping=mapping;
    }
    public boolean useM3() { return false; }
    public Connection openConnection() throws SQLException { throw new UnsupportedOperationException("ONN Core is not a JDBC data source"); }
    /** Probe the authenticated ONN HTTP API instead of routing a non-JDBC source through JDBC metadata. */
    @Override
    public JSONObject testConnection() throws Exception {
        return new DataAdapterConnectionTester().test(() -> {
            long started = System.nanoTime();
            OnnCoreSchema schema = new OnnCoreSchema(client.schema());
            Map<String, Object> ontology = schema.ontology();
            List<?> classes = (List<?>) ontology.getOrDefault("classlist", List.of());
            Map<?, ?> relationships = (Map<?, ?>) ontology.getOrDefault("relationship_rule", Map.of());
            JSONArray tables = new JSONArray();
            for (Object className : classes) {
                tables.add(JSONObject.of("name", className.toString(), "type", "TABLE"));
            }
            JSONObject report = new JSONObject();
            report.put("kind", "catalog");
            report.put("databaseProduct", "ONN Core");
            report.put("databaseVersion", "");
            report.put("driverName", "ONN Core HTTP");
            report.put("driverVersion", "");
            report.put("catalog", client.baseUrl());
            report.put("schema", schema.fingerprint);
            report.put("tables", tables);
            report.put("classes", classes.size());
            report.put("relationships", relationships.size());
            report.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
            return report;
        });
    }
    public DataAdapterCapabilities capabilities() { return DataAdapterCapabilities.onnReadOnly(); }
    // Deliberately separate from legacy objectWrite: arbitrary predicate updates, sandbox writes
    // and existing Python Actions must not acquire write access by the existence of this API.
    @Override public JSONObject readVersionedObject(String className, String id, Principal principal) throws Exception {
        try { return new OnnCoreConditionalWriter(client, mapping, principal).read(className, id); }
        catch (OnnCoreHttpClient.OnnHttpException e) { throw new Rejected(e.status, e.code); }
    }
    @Override public JSONObject patchObject(String className, String id, JSONObject values, String expectedEtag,
            String idempotencyKey, Principal principal) throws Exception {
        try { return new OnnCoreConditionalWriter(client, mapping, principal).patch(className, id, values, expectedEtag, idempotencyKey); }
        catch (OnnCoreHttpClient.OnnHttpException e) { throw new Rejected(e.status, e.code); }
    }
    public Map<String, Object> readOntology() throws Exception { return new OnnCoreSchema(client.schema()).ontology(); }
    public String objectPrimaryKey(Map<String, Object> classDef) { return "id"; }
    public void validateClassNameFormat(String className) { OnnCoreSchema.validatePath(className); }
    public void validatePrimaryKeyName(String attrName) throws Exception {
        if (!"id".equals(attrName)) throw new IllegalArgumentException("ONN Core object identity is the reserved id field");
    }

    public String executeDsl(String json, String sessionId, DslExecutionMode mode, List<String> generated) throws Exception {
        long started = System.nanoTime();
        try { return executeCached(json, sessionId, mode, generated, started); }
        catch (Exception error) { OnnCoreMetadataCache.invalidate(client); throw error; }
    }
    private String executeCached(String json, String sessionId, DslExecutionMode mode, List<String> generated, long started) throws Exception {
        JSONArray dsls=JSON.parseArray(json);
        if(dsls==null || dsls.size()!=1) throw new UnsupportedOperationException("ONN Core supports one query per execution");
        OnnCoreSchema schema=OnnCoreMetadataCache.schema(client, generated);
        if (OnnCoreGraphCompiler.isGraph(dsls.getJSONObject(0))) return executeGraph(dsls.getJSONObject(0), schema, generated);
        OnnCoreDslCompiler plan=new OnnCoreDslCompiler(dsls.getJSONObject(0),schema,mapping);
        List<JSONObject> rows=new ArrayList<>();
        JSONArray nativeAnswer=null;
        JSONObject evidence=JSONObject.of("backend","onn-core","view","BASE","schemaFingerprint",schema.fingerprint,
            "evaluation","bounded-count-checked-single-class","source",client.baseUrl(),"snapshotConsistent",false);
        List<String> ids=plan.identitySelection();
        if(ids!=null) {
            for(String id:ids) {
                generated.add("GET /api/v1/data/instance?class_path="+OnnCoreHttpClient.encode(plan.className)+"&id="+OnnCoreHttpClient.encode(id));
                JSONObject instance=client.instance(plan.className,id);
                if(instance!=null) {
                    JSONObject row=schema.flatten(plan.className,instance);
                    if(!id.equals(row.getString("id"))) throw new IllegalStateException("ONN returned a mismatched instance ID");
                    rows.add(row);
                }
            }
        } else {
            OnnCoreCapabilities negotiated=OnnCoreMetadataCache.capabilities(client, generated);
            if (negotiated.pushdown()) return executePushedScalar(dsls.getJSONObject(0), schema, plan, negotiated, generated, started);
            evidence.put("backendCommit",negotiated.commit());
            if (negotiated.snapshotReads()) {
                JSONObject nativeRead=plan.nativeSnapshotRead();
                boolean nativeCount=plan.nativeIdentityCount();
                int pageSize=nativeCount ? 1 : negotiated.pageSize();
                JSONObject loggedRead=JSON.parseObject(nativeRead.toJSONString());
                loggedRead.put("pagination",JSONObject.of("mode","snapshot","result_kind","data","page_size",pageSize));
                generated.add("POST /api/v1/query/execute " + JSON.toJSONString(loggedRead));
                OnnCoreSnapshotReader.Result snapshot=new OnnCoreSnapshotReader(client).read(nativeRead,pageSize,nativeCount);
                for (JSONObject instance:snapshot.rows()) rows.add(schema.flatten(plan.className,instance));
                if (nativeCount) nativeAnswer=plan.evaluateIdentityCount(snapshot.total());
                OnnCoreMetadataCache.validate(client, schema, generated);
                evidence.put("evaluation",nativeCount ? "snapshot-native-identity-count" : "snapshot-single-class-residual-evaluation");
                evidence.put("snapshotConsistent",true);
                evidence.put("consistency","materialized");
                evidence.put("dataVersion",snapshot.dataVersion());
                evidence.put("pagesRead",snapshot.pages());
                evidence.put("readAttempts",snapshot.attempts());
                evidence.put("snapshotReleased",snapshot.snapshotReleased());
                evidence.put("totalExact",true);
                evidence.put("complete",true);
                evidence.put("queryPushdown",nativeCount || nativeRead.containsKey("where_logic") || nativeRead.containsKey("output"));
                evidence.put("filterPushdown",nativeRead.containsKey("where_logic"));
                evidence.put("projectionPushdown",nativeRead.containsKey("output"));
                evidence.put("aggregatePushdown",nativeCount);
                evidence.put("residualEvaluation",!nativeCount);
                evidence.put("matchedObjects",snapshot.total());
            } else {
                rows.addAll(readLegacy(plan,schema,generated));
            }
        }
        JSONObject result=new JSONObject();
        if (ids != null) OnnCoreMetadataCache.validate(client, schema, generated);
        result.put("answer",nativeAnswer==null ? plan.evaluate(rows) : nativeAnswer); result.put("mqls",generated);
        evidence.put("objectsRead",rows.size());
        result.put("evidence",evidence);
        return JSON.toJSONString(List.of(result),Feature.WriteMapNullValue);
    }

    private String executeGraph(JSONObject dsl, OnnCoreSchema schema, List<String> generated) throws Exception {
        long started = System.nanoTime();
        OnnCoreCapabilities negotiated = OnnCoreMetadataCache.capabilities(client, generated);
        OnnCoreGraphCompiler plan = new OnnCoreGraphCompiler(dsl, schema, mapping, negotiated.pushdown());
        if (!negotiated.bindingReads()) throw new UnsupportedOperationException("ONN graph queries require enabled v1 pipeline bindings and v2 materialized binding pagination");
        if (negotiated.pushdown()) {
            boolean aggregate = negotiated.returnReads() && plan.nativeAggregate();
            JSONObject nativeQuery = aggregate ? plan.aggregatePipeline() : plan.projectedPipeline();
            String kind = aggregate ? "return" : "bindings";
            generated.add("POST /api/v1/query/pipeline " + nativeQuery.toJSONString());
            long remoteStart = System.nanoTime();
            var result = new OnnCoreSnapshotReader(client).readNative(nativeQuery, negotiated.pageSize(), kind, true,
                aggregate ? null : plan.bindings(), negotiated.inlinePages(), true);
            double remoteMs = elapsed(remoteStart);
            OnnCoreMetadataCache.validate(client, schema, generated);
            long localStart = System.nanoTime();
            JSONArray answer = aggregate ? plan.evaluateAggregate(result) : plan.evaluate(result.rows());
            JSONObject evidence = pushedEvidence(schema, negotiated, result, started, remoteMs, elapsed(localStart));
            evidence.put("relationshipPushdown", true); evidence.put("aggregatePushdown", aggregate);
            evidence.put("projectionPushdown", nativeQuery.containsKey("binding_output"));
            evidence.put("filterPushdown", hasFilter(nativeQuery));
            evidence.put("residualEvaluation", true); evidence.put("bindingsRead", aggregate ? 0 : result.rows().size());
            evidence.put("resultKind", kind);
            if (!aggregate) evidence.put("aggregateFallback", "Only equivalent count aggregates are pushed; typed grouping, exact arithmetic and deterministic ties remain residual");
            return encoded(answer, generated, evidence);
        }
        JSONObject query = plan.nativePipeline();
        generated.add("POST /api/v1/query/pipeline " + query.toJSONString());
        OnnCoreSnapshotReader.Result snapshot = new OnnCoreSnapshotReader(client).readBindings(query, negotiated.pageSize(), plan.bindings());
        OnnCoreMetadataCache.validate(client, schema, generated);
        JSONObject evidence = JSONObject.of("backend", "onn-core", "view", "BASE", "source", client.baseUrl(),
            "schemaFingerprint", schema.fingerprint, "backendCommit", negotiated.commit(),
            "evaluation", "snapshot-bindings-residual-evaluation", "snapshotConsistent", true,
            "consistency", "materialized", "dataVersion", snapshot.dataVersion(), "pagesRead", snapshot.pages(),
            "readAttempts", snapshot.attempts(), "snapshotReleased", snapshot.snapshotReleased(),
            "totalExact", true, "complete", true, "queryPushdown", true, "relationshipPushdown", true,
            "aggregatePushdown", false, "residualEvaluation", true, "bindingsRead", snapshot.rows().size());
        return JSON.toJSONString(List.of(JSONObject.of("answer", plan.evaluate(snapshot.rows()), "mqls", generated, "evidence", evidence)), Feature.WriteMapNullValue);
    }

    private String executePushedScalar(JSONObject dsl, OnnCoreSchema schema, OnnCoreDslCompiler plan,
            OnnCoreCapabilities negotiated, List<String> generated, long started) throws Exception {
        boolean count = plan.nativeMetricsCount();
        JSONObject object = dsl.getJSONObject("answer").getJSONArray("steps").getJSONObject(0)
            .getJSONObject("graph").getJSONArray("patterns").getJSONObject(0).getJSONArray("objects").getJSONObject(0);
        String variable = object.getString("variable");
        OnnCoreAggregatePlan aggregate = !count && negotiated.returnReads() && plan.exactPredicates()
            ? OnnCoreAggregatePlan.compile(dsl, schema, Map.of(variable, plan.className)) : null;
        JSONObject query = count ? plan.nativeCountRead() : plan.nativeSnapshotRead(true);
        String kind = count ? "metrics" : aggregate != null ? "return" : "data";
        if (aggregate != null) {
            query.remove("output");
            query = JSONObject.of("steps", List.of(JSONObject.of("name", variable, "graph", query)), "return", aggregate.returns);
        }
        generated.add("POST /api/v1/query/" + (aggregate == null ? "execute " : "pipeline ")
            + JSON.toJSONString(query, Feature.WriteMapNullValue));
        long remoteStart = System.nanoTime();
        var result = new OnnCoreSnapshotReader(client).readNative(query, count ? 1 : negotiated.pageSize(), kind,
            aggregate != null, null, negotiated.inlinePages(), true);
        double remoteMs = elapsed(remoteStart);
        OnnCoreMetadataCache.validate(client, schema, generated);
        long localStart = System.nanoTime();
        JSONArray answer = count ? plan.evaluateIdentityCount(result.total()) : aggregate != null ? aggregate.evaluate(result)
            : plan.evaluate(result.rows().stream().map(row -> schema.flatten(plan.className, row)).toList());
        JSONObject evidence = pushedEvidence(schema, negotiated, result, started, remoteMs, elapsed(localStart));
        evidence.put("objectsRead", count || aggregate != null ? 0 : result.rows().size());
        evidence.put("filterPushdown", hasFilter(query));
        evidence.put("projectionPushdown", query.containsKey("output"));
        evidence.put("aggregatePushdown", count || aggregate != null); evidence.put("resultKind", kind);
        evidence.put("residualEvaluation", !count);
        evidence.put("matchedObjects", count || aggregate == null ? result.total() : null);
        if (!count && aggregate == null) evidence.put("aggregateFallback", "Precision-sensitive aggregates, typed group keys and unsupported predicates retain complete local evaluation");
        return encoded(answer, generated, evidence);
    }
    private JSONObject pushedEvidence(OnnCoreSchema schema, OnnCoreCapabilities capabilities, OnnCoreSnapshotReader.Result result,
            long started, double remoteMs, double localMs) {
        return JSONObject.of("backend", "onn-core", "view", "BASE", "source", client.baseUrl(),
            "schemaFingerprint", schema.fingerprint, "backendCommit", capabilities.commit(), "backendBuild", capabilities.buildIdentity(),
            "evaluation", "t30-negotiated-pushdown", "snapshotConsistent", true, "consistency", "materialized",
            "dataVersion", result.dataVersion(), "pagesRead", result.pages(), "readAttempts", result.attempts(),
            "snapshotReleased", result.snapshotReleased(), "complete", true, "totalExact", true, "queryPushdown", true,
            "onnProfiles", result.profiles(), "timingMs", JSONObject.of("total", elapsed(started), "remoteRead", remoteMs, "residual", localMs));
    }
    private static double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000d; }
    private static boolean hasFilter(Object node) {
        if (node instanceof Map<?, ?> object) return object.containsKey("where_logic") || object.values().stream().anyMatch(OnnCoreAdapter::hasFilter);
        if (node instanceof List<?> array) return array.stream().anyMatch(OnnCoreAdapter::hasFilter);
        return false;
    }
    private static String encoded(JSONArray answer, List<String> generated, JSONObject evidence) {
        return JSON.toJSONString(List.of(JSONObject.of("answer", answer, "mqls", generated, "evidence", evidence)), Feature.WriteMapNullValue);
    }

    private List<JSONObject> readLegacy(OnnCoreDslCompiler plan,OnnCoreSchema schema,List<String> generated) throws Exception {
        List<JSONObject> rows=new ArrayList<>();
        long before = classCount(plan.className,generated);
        if (before > OnnCoreDslCompiler.MAX_ROWS) throw new UnsupportedOperationException(
            "ONN class exceeds the initial 5000-object read limit; use an exact id filter or a smaller class");
        JSONObject nativeRead=plan.nativeRead();
        generated.add("POST /api/v1/query/execute " + JSON.toJSONString(nativeRead));
        JSONObject response=client.query(nativeRead);
        JSONArray data=response.getJSONArray("data"); JSONObject metrics=response.getJSONObject("metrics");
        if(data==null || response.get("matched_count")==null
            || metrics==null || metrics.get("total")==null) throw new IllegalStateException("ONN query did not provide a consistent data/count response");
        long matched=count(response.get("matched_count"),"matched count");
        if(matched!=data.size()) throw new IllegalStateException("ONN query did not provide a consistent data/count response");
        long total=count(metrics.get("total"),"total count");
        if(total>OnnCoreDslCompiler.MAX_ROWS || data.size()>OnnCoreDslCompiler.MAX_ROWS)
            throw new UnsupportedOperationException("ONN class exceeds the initial 5000-object complete-read limit; use an exact id filter or a smaller class");
        if(total!=data.size()) throw new IllegalStateException("ONN query is incomplete; no answer was produced");
        Set<String> seen=new HashSet<>();
        for(Object raw:data) {
            JSONObject row=schema.flatten(plan.className,JSONObject.from(raw));
            if(!seen.add(row.getString("id"))) throw new IllegalStateException("ONN returned duplicate instance identities");
            rows.add(row);
        }
        long after = classCount(plan.className,generated);
        if (before != rows.size() || after != before) throw new IllegalStateException(
            "ONN class count changed or instances were omitted; no answer was produced");
        return rows;
    }

    private long classCount(String className,List<String> generated) throws Exception {
        generated.add("GET /api/v1/data/sample?class="+OnnCoreHttpClient.encode(className)+"&limit=1");
        JSONObject sample = client.sample(className);
        if (!className.equals(sample.getString("class")) || sample.get("total_count") == null)
            throw new IllegalStateException("ONN did not provide a class count for completeness checking");
        return count(sample.get("total_count"),"class count");
    }

    private static long count(Object raw,String kind) {
        try {
            long value=new java.math.BigDecimal(raw.toString()).longValueExact();
            if(value<0) throw new IllegalArgumentException();
            return value;
        } catch(RuntimeException e) { throw new IllegalStateException("ONN returned an invalid "+kind,e); }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> query(JSONObject dsl,String sandbox,String domain,VectorResourceDao vectorDao,DataRagConfig config) throws Exception {
        if(sandbox!=null && !sandbox.isBlank()) capabilities().requireSandbox();
        String normalized=DslUtil.normalizeClassNames(JSON.toJSONString(List.of(dsl),Feature.WriteMapNullValue),
            (List<Map<String,Object>>)mapping.get("classDef"));
        DslExecutionResult[] results=HttpRequestUtil.executeDsl(config,this,normalized,null,true,mapping,vectorDao,new UserDataPermission(),DslExecutionMode.OPTIMIZED,domain);
        DslExecutionResult result=results[1];
        Map<String,Object> data=new LinkedHashMap<>(result.data());
        if(result.failed()) data.put("error",result.failure().cause().getMessage());
        return data;
    }

    private synchronized OnnCoreSchema schemaForSamples() {
        if(sampleSchema==null) try { sampleSchema=new OnnCoreSchema(client.schema()); }
        catch(Exception e) { throw new IllegalStateException("Unable to read ONN schema for samples",e); }
        return sampleSchema;
    }
    public String getSampleDataFileName(String dir,String className) {
        OnnCoreSchema schema=schemaForSamples(); schema.requireClass(className);
        return "conf/"+dir+"/onn-core/"+OnnCoreSchema.digest(client.cacheScope()+"\n"+schema.fingerprint+"\n"+JSON.toJSONString(mapping)+"\n"+className)+".json";
    }
    public String querySampleDataByClassName(String className,List<String> attrs) {
        OnnCoreSchema schema=schemaForSamples(); schema.requireClass(className);
        Set<String> disabled=schema.disabledSampleFields(mapping,className);
        try {
            // Return all enabled scalar fields so a cached sample never depends on a prior field selection.
            JSONObject result=client.sample(className); JSONArray samples=result.getJSONArray("samples");
            if(samples==null) throw new IllegalStateException("ONN sample response is missing samples");
            if(samples.isEmpty()) return className+" class has no data";
            JSONObject row=schema.flatten(className,samples.getJSONObject(0));
            for(String field:new ArrayList<>(row.keySet())) {
                if(disabled.contains(field)) { row.remove(field); continue; }
                schema.validateMapping(mapping,className,Set.of(field));
                Object value=row.get(field);
                if(value instanceof String str && str.length()>100) row.put(field,str.substring(0,100)+"...");
            }
            return JSON.toJSONString(row,Feature.WriteMapNullValue);
        } catch(Exception e) { throw new IllegalStateException("Unable to read ONN sample for "+className,e); }
    }

    private static UnsupportedOperationException unavailable() { return new UnsupportedOperationException("ONN Core business data is read only; writes, sandbox and document-vector fields are unavailable"); }
    // Keep the adapter-level schema hooks explicit for callers compiled against the
    // compatibility surface; ONN Core schema writes are intentionally unavailable in
    // the read-only first phase.
    public void createSchemaClass(Map<String, Object> c, DataRagConfig config) { throw unavailable(); }
    public void deleteSchemaClass(String className, DataRagConfig config) { throw unavailable(); }
    public void createSchemaRelationship(String name, Map<String, String> relation, DataRagConfig config) { throw unavailable(); }
    public void deleteSchemaRelationship(String name, Map<String, String> relation, DataRagConfig config) { throw unavailable(); }
    public void addSchemaAttribute(String className, Map<String, Object> attr, DataRagConfig config) { throw unavailable(); }
    public void deleteSchemaAttribute(String className, String attrName, DataRagConfig config) { throw unavailable(); }
    public void createNamespace(String n) { throw unavailable(); }
    public void truncateNamespace(String n) { throw unavailable(); }
    public void dropNamespace(String n) { throw unavailable(); }
    public void createClass(String n,Map<String,Object> c) { throw unavailable(); }
    public void createEdgeType(String n,String e) { throw unavailable(); }
    public void insertObjects(String n,Map<String,Object> c,JSONArray o) { throw unavailable(); }
    public void updateObjects(String n,Map<String,Object> c,JSONObject s,JSONObject w) { throw unavailable(); }
    public void deleteObjects(String n,Map<String,Object> c,JSONObject w) { throw unavailable(); }
    public void createEdge(String n,String r,String sc,String si,String tc,String ti) { throw unavailable(); }
    public void deleteEdge(String n,String r,String sc,String si,String tc,String ti) { throw unavailable(); }
    public JSONArray queryVectorAttr(Map<String,Object> c,String a,String id) { throw unavailable(); }
    public String sandboxClassName(String sandbox,String className) { throw unavailable(); }
    public String sandboxObjectId(String sandbox,String id) { throw unavailable(); }
}
