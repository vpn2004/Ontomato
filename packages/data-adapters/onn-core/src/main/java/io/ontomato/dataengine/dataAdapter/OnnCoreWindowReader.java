package io.ontomato.dataengine.dataAdapter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.*;

// Explicit windows discard accumulated pages on retry while retaining the total read budget.
final class OnnCoreWindowReader {
    static final String ORDERING = "adapter_identity_json_utf16_v1";
    private final OnnCoreHttpClient client;
    private final int maxRows;
    private final long maxBytes;
    OnnCoreWindowReader(OnnCoreHttpClient client) {this(client, 100_000, 32L * 1024 * 1024);}
    OnnCoreWindowReader(OnnCoreHttpClient client, int maxRows, long maxBytes) {
        if (maxRows < 1 || maxBytes < 1) throw new IllegalArgumentException("Invalid ONN window read budget");
        this.client = client; this.maxRows = maxRows; this.maxBytes = maxBytes;
    }
    record Result(List<JSONObject> rows,long total,String version,int pages,int attempts,boolean released,boolean sourceExhausted,List<JSONObject> profiles) { }

    Result read(JSONObject original,int pageSize,int needed,Map<String,String> bindings)throws Exception {
        if(pageSize<1||pageSize>1000||needed<0||needed>100_000||bindings==null||bindings.isEmpty()||original.containsKey("pagination"))throw new IllegalArgumentException("Invalid ONN binding window");
        long deadline=System.nanoTime()+Duration.ofSeconds(45).toNanos();
        ReadBudget budget = new ReadBudget();
        int restarts=0, capacityWaits=0;
        for(int attempt=1;;attempt++) {
            try{return attempt(original,pageSize,needed,bindings,deadline,budget,attempt);}
            catch(OnnCoreHttpClient.OnnHttpException error){
                long delay;
                if(error.status==429&&"SNAPSHOT_CAPACITY_EXCEEDED".equals(error.code)) {
                    if(capacityWaits>=8)throw error;
                    delay=Math.min(1000L,100L<<capacityWaits++);
                } else {
                    boolean restart=error.status==409&&Set.of("SNAPSHOT_BUSY","SNAPSHOT_STALE","SNAPSHOT_AUTH_CHANGED").contains(error.code)||error.status==410&&"CURSOR_EXPIRED".equals(error.code);
                    if(!restart||++restarts>=3)throw error;
                    delay=50L*restarts;
                }
                Thread.sleep(Math.min(delay,Math.max(1,remaining(deadline).toMillis())));
            }
        }
    }
    private Result attempt(JSONObject original,int pageSize,int needed,Map<String,String> bindings,long deadline,ReadBudget budget,int attempt)throws Exception {
        JSONObject query=JSON.parseObject(JSON.toJSONString(original,com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue));
        JSONObject pagination=JSONObject.of("mode","version_checked","result_kind","bindings","binding_window",true,"binding_ordering",ORDERING,"page_size",pageSize);query.put("pagination",pagination);
        List<JSONObject> rows=new ArrayList<>(),profiles=new ArrayList<>();Set<String> identities=new HashSet<>(),cursors=new HashSet<>();
        String close=null,version=null,previous=null;long total=-1;int pages=0;boolean exhausted=false,released=false;
        try {
            do {
                JSONObject response=client.page(query,remaining(deadline),true,true);JSONObject meta=response.getJSONObject("pagination");
                String token=meta==null?null:meta.getString("close_token");if(token!=null&&!token.isBlank()&&close==null)close=token;
                if(meta==null||!Boolean.TRUE.equals(meta.get("total_exact"))||!ORDERING.equals(meta.getString("ordering_contract"))||!"version_checked".equals(meta.getString("consistency"))||!"bindings".equals(meta.getString("result_kind"))||meta.get("snapshot_id")!=null||meta.get("fallback_reason")!=null||Boolean.TRUE.equals(response.get("truncated"))||!(meta.get("has_more") instanceof Boolean))throw invalid("window metadata");
                long current=count(meta.get("total"));String currentVersion=meta.getString("data_version");
                if(currentVersion==null||currentVersion.isBlank()||currentVersion.length()>256||current>0&&(token==null||token.isBlank()))throw invalid("version or close token");
                if(pages==0){total=current;version=currentVersion;}else if(total!=current||!version.equals(currentVersion)||!Objects.equals(close,token))throw invalid("window identity changed");
                var data=response.getJSONArray("bindings");if(data==null&&count(meta.get("page_count"))==0&&response.containsKey("bindings"))data=new com.alibaba.fastjson2.JSONArray();
                if(data==null||data.size()!=count(meta.get("page_count"))||data.size()!=Math.min(pageSize,total-rows.size()))throw invalid("page size mismatch");
                budget.charge(response, data.size());
                for(Object raw:data){JSONObject row=JSONObject.from(raw);String identity=OnnCoreGraphCompiler.bindingIdentity(row,bindings);if(!identities.add(identity)||previous!=null&&previous.compareTo(identity)>=0)throw invalid("duplicate or unordered identity");previous=identity;rows.add(row);}
                if(rows.size()>total)throw invalid("rows exceed exact total");
                if(response.getJSONObject("query_profile")!=null)profiles.add(response.getJSONObject("query_profile"));
                pages++;boolean more=meta.getBooleanValue("has_more");String cursor=meta.getString("next_cursor");
                if(more!=(rows.size()<total)||more&&(data.isEmpty()||cursor==null||cursor.isBlank()||!cursors.add(cursor))||!more&&cursor!=null&&!cursor.isBlank())throw invalid("continuation mismatch");
                exhausted=!more;if(exhausted||rows.size()>=needed)break;
                pagination.put("after",cursor);
            }while(true);
        }finally {
            if(close!=null){try{client.closeWindow(close,Duration.ofSeconds(1));released=true;}catch(Exception error){if(error instanceof InterruptedException)Thread.currentThread().interrupt();}}
        }
        return new Result(List.copyOf(rows),total,version,pages,attempt,released,exhausted,List.copyOf(profiles));
    }
    private final class ReadBudget {
        private long rows, bytes;
        void charge(JSONObject response, int pageRows) {
            // Failed attempts still consumed resources; only their result rows are discarded.
            rows += pageRows;
            bytes += JSON.toJSONBytes(response).length;
            if (rows > maxRows) throw invalid("row budget");
            if (bytes > maxBytes) throw invalid("byte budget");
        }
    }
    private static long count(Object value){try{long n=new java.math.BigDecimal(value.toString()).longValueExact();if(n>=0)return n;}catch(RuntimeException ignored){}throw invalid("invalid count");}
    private static Duration remaining(long deadline)throws HttpTimeoutException {long n=deadline-System.nanoTime();if(n<=0)throw new HttpTimeoutException("ONN window deadline exceeded");return Duration.ofNanos(n);}
    private static IllegalStateException invalid(String reason){return new IllegalStateException("ONN binding window is incomplete: "+reason+"; no answer was produced");}
}
