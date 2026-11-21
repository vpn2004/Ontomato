package io.ontomato.dataengine.dao;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import io.ontomato.dataengine.bean.abcHarness.ABCHarnessCheck;

class CheckDaoV2PhaseTest {
    @Test void roundTripsProgressThroughTheActualDaoColumnMapping() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var dao = new CheckDaoV2();
        ReflectionTestUtils.setField(dao, "jdbcTemplate", jdbc);
        Map<String, Object> stored = new HashMap<>();
        doAnswer(call -> {
            String sql = call.getArgument(0);
            String[] columns = sql.substring(sql.indexOf('(') + 1, sql.indexOf(')')).split(",\\s*");
            Object[] values = (Object[]) call.getRawArguments()[1];
            for (int i = 0; i < columns.length; i++) stored.put(columns[i], values[i]);
            return 1;
        }).when(jdbc).update(anyString(), any(Object[].class));
        var rs = mock(ResultSet.class);
        when(rs.getObject(anyString())).thenAnswer(call -> stored.get(call.getArgument(0)));
        when(rs.getString(anyString())).thenAnswer(call -> {
            Object value = stored.get(call.getArgument(0)); return value == null ? null : value.toString();
        });
        when(rs.getInt(anyString())).thenAnswer(call -> ((Number) stored.get(call.getArgument(0))).intValue());
        when(rs.getBoolean(anyString())).thenAnswer(call -> stored.get(call.getArgument(0)));
        doAnswer(call -> {
            RowMapper<?> mapper = call.getArgument(1); return List.of(mapper.mapRow(rs, 0));
        }).when(jdbc).query(anyString(), any(RowMapper.class), eq("session"));
        var check = new ABCHarnessCheck(); check.setSessionId("session");
        check.setPhase("assessing"); check.setTotalPrograms(9); check.setFinished(false);
        dao.save(check);
        var restored = dao.queryBySessionId("session");
        assertNotNull(restored);
        assertEquals("assessing", restored.getPhase());
        assertEquals(9, restored.getTotalPrograms());
        assertFalse(restored.getFinished());
    }
}
