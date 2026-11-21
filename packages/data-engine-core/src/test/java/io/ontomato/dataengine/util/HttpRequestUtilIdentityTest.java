package io.ontomato.dataengine.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.ontomato.dataengine.dao.VectorResourceDao;
import io.ontomato.dataengine.dataAdapter.DataAdapter;
import io.ontomato.dataengine.dataAdapter.DataAdapterCapabilities;
import io.ontomato.dataengine.service.sys.bean.permission.UserDataPermission;

class HttpRequestUtilIdentityTest {
    private static final Map<String, Object> DEVICE = Map.of("className", "/ns/device", "attrs", List.of(
            Map.of("name", "code", "type", "varchar", "primaryKey", true),
            Map.of("name", "name", "type", "varchar")));
    private static final String DSL = """
            [{"answer":{"steps":[{
              "graph":{"patterns":[{"objects":[{"variable":"device","class":"/ns/device"}]}]},
              "output":{"fields":[{"variable":"device","field":"name","as":"device_name"}]}}]}}]
            """;

    @Test
    void relationalEvidenceProjectsTheAdapterKeyAndEncodesNamespacedIdsWithoutLosingColons() throws Exception {
        DataAdapter adapter = mock(DataAdapter.class, CALLS_REAL_METHODS);
        AtomicReference<String> executed = new AtomicReference<>();
        doAnswer(call -> {
            executed.set(call.getArgument(0));
            return "[{\"answer\":[{\"device_id\":\"tenant:device:42\"}]}]";
        }).when(adapter).executeDsl(anyString(), any(), any(), any());

        List<String> evidence = evidence(adapter, DSL, List.of(DEVICE));

        assertEquals(List.of("/ns/device:tenant:device:42"), evidence);
        assertEquals("code", identityProjection(executed.get()).getString("field"));
        assertEquals("tenant:device:42", adapter.decodeNodeReference("/ns/device", evidence.get(0)));
    }

    @Test
    void virtualBackendIdentityOverridesTheOrdinaryDeclaredAttributeKey() throws Exception {
        DataAdapter adapter = mock(DataAdapter.class, CALLS_REAL_METHODS);
        doReturn(DataAdapterCapabilities.onnReadOnly()).when(adapter).capabilities();
        doReturn("id").when(adapter).objectPrimaryKey(any());
        AtomicReference<String> executed = new AtomicReference<>();
        doAnswer(call -> {
            executed.set(call.getArgument(0));
            return "[{\"answer\":[{\"device_id\":\"native:id:42\"}]}]";
        }).when(adapter).executeDsl(anyString(), any(), any(), any());

        List<String> evidence = evidence(adapter, DSL, List.of(DEVICE));

        assertEquals("id", identityProjection(executed.get()).getString("field"));
        assertEquals(List.of("/ns/device:native:id:42"), evidence);
    }

    @Test
    void m3EvidenceKeepsItsNativeNodeReferences() throws Exception {
        DataAdapter adapter = mock(DataAdapter.class, CALLS_REAL_METHODS);
        when(adapter.useM3()).thenReturn(true);
        AtomicReference<String> executed = new AtomicReference<>();
        doAnswer(call -> {
            executed.set(call.getArgument(0));
            return "[{\"answer\":[{\"device_id\":\"/ns/device:tenant:42\"}]}]";
        }).when(adapter).executeDsl(anyString(), any(), any(), any());

        List<String> evidence = evidence(adapter, DSL, List.of(DEVICE));

        assertEquals("id", identityProjection(executed.get()).getString("field"));
        assertEquals(List.of("/ns/device:tenant:42"), evidence);
    }

    @Test
    void identicalBackendIdsFromDifferentClassesRemainDistinctAndNullJoinsAreIgnored() throws Exception {
        DataAdapter adapter = mock(DataAdapter.class, CALLS_REAL_METHODS);
        doReturn("[{\"answer\":[{\"device_id\":\"same:42\",\"site_id\":\"same:42\"},"
                + "{\"device_id\":\"same:42\",\"site_id\":null}]}]")
                .when(adapter).executeDsl(anyString(), any(), any(), any());
        String joined = """
                [{"answer":{"steps":[{
                  "graph":{"patterns":[{"objects":[{"variable":"device","class":"/ns/device"},
                                                       {"variable":"site","class":"/ns/site"}]}]},
                  "output":{"fields":[{"variable":"device","field":"name","as":"device_name"},
                                        {"variable":"site","field":"name","as":"site_name"}]}}]}}]
                """;
        Map<String, Object> site = Map.of("className", "/ns/site", "attrs", DEVICE.get("attrs"));

        List<String> evidence = evidence(adapter, joined, List.of(DEVICE, site));

        assertEquals(Set.of("/ns/device:same:42", "/ns/site:same:42"), Set.copyOf(evidence));
        assertEquals(2, evidence.size());
    }

    @Test
    void backendEvidenceFailureIsReportedInsteadOfBecomingAnEmptyEvidenceList() throws Exception {
        DataAdapter adapter = mock(DataAdapter.class, CALLS_REAL_METHODS);
        doThrow(new UnsupportedOperationException("Relationship evidence is unsupported"))
                .when(adapter).executeDsl(anyString(), any(), any(), any());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> evidence(adapter, DSL, List.of(DEVICE)));

        assertTrue(error.getMessage().contains("Relationship evidence is unsupported"));
        assertTrue(error.getCause() instanceof UnsupportedOperationException);
    }

    private static List<String> evidence(DataAdapter adapter, String dsl, List<Map<String, Object>> definitions) {
        UserDataPermission permission = new UserDataPermission();
        permission.setFullData(true);
        return HttpRequestUtil.getNodeIds(adapter, dsl, Map.of("classDef", definitions),
                mock(VectorResourceDao.class), permission, "d1");
    }

    private static JSONObject identityProjection(String dsl) {
        return JSON.parseArray(dsl).getJSONObject(0).getJSONObject("answer").getJSONArray("steps")
                .getJSONObject(0).getJSONObject("output").getJSONArray("fields").getJSONObject(0);
    }
}
