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
public final class OnnCoreAdapter implements DataAdapter {
    private final OnnCoreHttpClient client;
    private final Map<String, Object> mapping;
    private OnnCoreSchema sampleSchema;

    public OnnCoreAdapter(DataAdapterConnection connection, Map<String, Object> mapping) {
        this.client = new OnnCoreHttpClient(connection.getUrl()); this.mapping=mapping;
    }
    public boolean useM3() { return false; }
    public Connection openConnection() throws SQLException { throw new UnsupportedOperationException("ONN Core is not a JDBC data source"); }
    public DataAdapterCapabilities capabilities() { return DataAdapterCapabilities.onnReadOnly(); }
    public Map<String, Object> readOntology() throws Exception { return new OnnCoreSchema(client.schema()).ontology(); }
    public String objectPrimaryKey(Map<String, Object> classDef) { return "id"; }
    public void validateClassNameFormat(String className) { OnnCoreSchema.validatePath(className); }
    public void validatePrimaryKeyName(String attrName) throws Exception {
        if (!"id".equals(attrName)) throw new IllegalArgumentException("ONN Core object identity is the reserved id field");
    }

    public String executeDsl(String json, String sessionId, DslExecutionMode mode, List<String> generated) throws Exception {
        JSONArray dsls=JSON.parseArray(json);
        if(dsls==null || dsls.size()!=1) throw new UnsupportedOperationException("ONN Core supports one query per execution");
        generated.add("GET /api/v1/schema/all");
        OnnCoreSchema schema=new OnnCoreSchema(client.schema());
        OnnCoreDslCompiler plan=new OnnCoreDslCompiler(dsls.getJSONObject(0),schema,mapping);
        List<JSONObject> rows=new ArrayList<>();
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
        }
        JSONObject result=new JSONObject();
        result.put("answer",plan.evaluate(rows)); result.put("mqls",generated);
        result.put("evidence",Map.of("backend","onn-core","view","BASE","schemaFingerprint",schema.fingerprint,
            "evaluation","bounded-count-checked-single-class","objectsRead",rows.size(),"source",client.baseUrl(),
            "snapshotConsistent",false));
        return JSON.toJSONString(List.of(result),Feature.WriteMapNullValue);
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
        return "conf/"+dir+"/onn-core/"+OnnCoreSchema.digest(client.baseUrl()+"\n"+schema.fingerprint+"\n"+JSON.toJSONString(mapping)+"\n"+className)+".json";
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
