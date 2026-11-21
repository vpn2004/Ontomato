package io.ontomato.dataengine.dao;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter.Feature;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Durable immutable decisions and execution evidence live in the platform database. */
@Component
public class ControlledActionDecisionDao {
    private final JdbcTemplate jdbc;
    public ControlledActionDecisionDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @PostConstruct public void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS controlled_action_decision (id VARCHAR(64) PRIMARY KEY, "
            + "domain_id VARCHAR(64) NOT NULL, decision TEXT NOT NULL, state VARCHAR(24) NOT NULL, "
            + "result TEXT, updated_at BIGINT NOT NULL, claim_token VARCHAR(64), attempts INTEGER NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_controlled_action_domain ON controlled_action_decision(domain_id, updated_at)");
    }
    public void insert(String domain, JSONObject decision) {
        jdbc.update("INSERT INTO controlled_action_decision(id,domain_id,decision,state,updated_at) VALUES(?,?,?,'PREPARED',?)",
            decision.getString("id"), domain, JSON.toJSONString(decision, Feature.WriteMapNullValue), System.currentTimeMillis());
    }
    public JSONObject get(String domain, String id) {
        var rows = jdbc.query("SELECT * FROM controlled_action_decision WHERE domain_id=? AND id=?", (rs, n) -> {
            JSONObject row = JSON.parseObject(rs.getString("decision"));
            row.put("state", rs.getString("state")); row.put("result", JSON.parse(rs.getString("result")));
            row.put("updatedAt", rs.getLong("updated_at")); row.put("attempts", rs.getInt("attempts"));
            return row;
        }, domain, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Decision does not exist in this domain");
        return rows.get(0);
    }
    public List<String> recent(String domain) {
        return jdbc.queryForList("SELECT id FROM controlled_action_decision WHERE domain_id=? ORDER BY updated_at DESC LIMIT 20", String.class, domain);
    }
    public boolean hasUncertain(String domain, String className, String objectId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM controlled_action_decision "
            + "WHERE domain_id=? AND state IN ('UNKNOWN','EXECUTING') "
            + "AND decision::jsonb->>'className'=? AND decision::jsonb->>'objectId'=?)", Boolean.class, domain, className, objectId));
    }
    /** Expired claims can only resend the identical backend decision; a unique claim fences late responses. */
    public boolean claim(String domain, String id, String token, long now) {
        return jdbc.update("UPDATE controlled_action_decision SET state='EXECUTING',claim_token=?,updated_at=?,attempts=attempts+1 "
            + "WHERE domain_id=? AND id=? AND (state IN ('PREPARED','UNKNOWN') OR (state='EXECUTING' AND updated_at<?))",
            token, now, domain, id, now - 120000) == 1;
    }
    public void finish(String domain, String id, String token, String state, JSONObject result) {
        jdbc.update("UPDATE controlled_action_decision SET state=?,result=?,updated_at=?,claim_token=NULL "
            + "WHERE domain_id=? AND id=? AND claim_token=? AND state='EXECUTING'", state,
            JSON.toJSONString(result, Feature.WriteMapNullValue), System.currentTimeMillis(), domain, id, token);
    }
}
