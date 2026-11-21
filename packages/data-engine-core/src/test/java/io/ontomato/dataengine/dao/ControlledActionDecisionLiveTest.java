package io.ontomato.dataengine.dao;

import com.alibaba.fastjson2.JSONObject;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Dedicated fixture database only; never runs from the normal test command. */
@EnabledIfEnvironmentVariable(named = "ONTOMATO_CONTROLLED_PG_LIVE_TEST", matches = "true")
class ControlledActionDecisionLiveTest {
    @Test void postgresClaimsFenceConcurrentExecutionsAndSurviveDaoReconstruction() throws Exception {
        var ds = new DriverManagerDataSource(System.getenv("ONTOMATO_TEST_PG_URL"), System.getenv("ONTOMATO_TEST_PG_USER"), System.getenv("ONTOMATO_TEST_PG_PASSWORD"));
        var jdbc = new JdbcTemplate(ds); var dao = new ControlledActionDecisionDao(jdbc); dao.init();
        String id = UUID.randomUUID().toString();
        dao.insert("fixture", JSONObject.of("id", id, "className", "/logistics/carrier", "objectId", "CR001", "expectedEtag", "\"original\"", "idempotencyKey", "action:" + id, "values", JSONObject.of("contract_level", "core")));
        long now = System.currentTimeMillis(); var pool = Executors.newFixedThreadPool(8);
        try {
            var calls = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 8; i++) { final String token = "claim-" + i; calls.add(() -> dao.claim("fixture", id, token, now)); }
            var results = pool.invokeAll(calls); assertEquals(1, results.stream().filter(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } }).count());
        } finally { pool.shutdownNow(); }
        assertThrows(IllegalArgumentException.class, () -> dao.get("other", id));
        assertTrue(dao.hasUncertain("fixture", "/logistics/carrier", "CR001"));
        assertFalse(dao.hasUncertain("other", "/logistics/carrier", "CR001"));
        jdbc.update("UPDATE controlled_action_decision SET updated_at=? WHERE id=?", now - 121000, id);
        assertTrue(dao.claim("fixture", id, "recovered", now));
        dao.finish("fixture", id, "claim-0", "SUCCEEDED", JSONObject.of("version", 99));
        assertEquals("EXECUTING", dao.get("fixture", id).getString("state"));
        dao.finish("fixture", id, "recovered", "UNKNOWN", JSONObject.of("code", "LOST_RESPONSE"));
        var restarted = new ControlledActionDecisionDao(new JdbcTemplate(ds));
        assertEquals("UNKNOWN", restarted.get("fixture", id).getString("state"));
        assertTrue(restarted.claim("fixture", id, "retry", now + 1));
        assertEquals("\"original\"", restarted.get("fixture", id).getString("expectedEtag"));
        assertEquals("action:" + id, restarted.get("fixture", id).getString("idempotencyKey"));
        restarted.finish("fixture", id, "retry", "SUCCEEDED", JSONObject.of("version", 8));
        assertFalse(restarted.claim("fixture", id, "duplicate", now + 2));
        assertFalse(restarted.hasUncertain("fixture", "/logistics/carrier", "CR001"));
        assertEquals(3, restarted.get("fixture", id).getIntValue("attempts"));
    }
}
