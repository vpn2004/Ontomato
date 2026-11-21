package io.ontomato.dataengine.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.ontomato.dataengine.config.BusinessConfig;
import io.ontomato.dataengine.config.DataRagConfig;
import io.ontomato.dataengine.dao.VectorResourceDao;
import io.ontomato.dataengine.dataAdapter.DataAdapter;
import io.ontomato.dataengine.dataAdapter.DataAdapterCapabilities;
import io.ontomato.dataengine.dataAdapter.DataAdapterRegistry;
import io.ontomato.dataengine.service.AdminService;
import io.ontomato.dataengine.service.BusinessConfigService;
import io.ontomato.dataengine.service.LangService;
import io.ontomato.dataengine.service.sys.bean.permission.UserDataPermission;

/** Exercises the real DSL transport boundary without a backend, not mocked service responses. */
class DataServiceImplQueryFailureTest {
    private final DataServiceImpl service = new DataServiceImpl();
    private final DataAdapter adapter = mock(DataAdapter.class, CALLS_REAL_METHODS);
    private final UserDataPermission permission = new UserDataPermission();

    @BeforeEach
    void wire() {
        BusinessConfig config = new BusinessConfig();
        config.setDataAdapter("onn-core");
        BusinessConfigService configs = mock(BusinessConfigService.class);
        when(configs.get("d1")).thenReturn(config);
        DataAdapterRegistry registry = mock(DataAdapterRegistry.class);
        when(registry.create(eq(config), any())).thenReturn(adapter);
        doReturn(DataAdapterCapabilities.onnReadOnly()).when(adapter).capabilities();
        AdminService admin = mock(AdminService.class);
        when(admin.getJSONRule("d1")).thenReturn(JSON.parseObject("""
                {"classlist":["/ns/device"],"relationship_rule":{},"classDef":[
                 {"className":"/ns/device","showName":"Device","attrs":[
                   {"name":"id","type":"varchar","primaryKey":true,"enable":true,"sourceName":"@identity"},
                   {"name":"name","type":"varchar","enable":true,"bizzkey":true,"sourceName":"name"}]}]}
                """));
        LangService lang = mock(LangService.class);
        when(lang.get(any(), anyString())).thenAnswer(call -> call.getArgument(1));
        DataRagConfig rag = new DataRagConfig();
        rag.setCardBaseUrl("http://fixture-card");
        ReflectionTestUtils.setField(service, "dataRagConfig", rag);
        ReflectionTestUtils.setField(service, "businessConfigService", configs);
        ReflectionTestUtils.setField(service, "dataAdapterRegistry", registry);
        ReflectionTestUtils.setField(service, "adminService", admin);
        ReflectionTestUtils.setField(service, "langService", lang);
        ReflectionTestUtils.setField(service, "vectorResourceDao", mock(VectorResourceDao.class));
        permission.setFullData(true);
    }

    @Test
    void rejectedPagedReadsReturnTheBackendFailureBeforeRunningCountQueries() throws Exception {
        for (String reason : new String[] {"Class exceeds 5000-object bound", "Relationship query is unsupported", "Request timed out"}) {
            clearInvocations(adapter);
            doThrow(new UnsupportedOperationException(reason)).when(adapter).executeDsl(anyString(), anyString(), any(), any());

            JSONObject result = service.getClassDataByPage(page(), "en", permission, "d1");

            assertFailure(result, reason);
            verify(adapter, times(1)).executeDsl(anyString(), anyString(), any(), any());
        }
    }

    @Test
    void failedWholeClassReadsReturnFalseWithoutZeroCounts() throws Exception {
        doThrow(new IllegalStateException("Source unavailable")).when(adapter).executeDsl(anyString(), anyString(), any(), any());

        JSONObject result = service.getWholeClassDataByPage(page(), "en", permission, "d1");

        assertFailure(result, "Source unavailable");
        verify(adapter, times(1)).executeDsl(anyString(), anyString(), any(), any());
    }

    @Test
    void countFailuresAfterSuccessfulRowsFailTheWholePage() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        doAnswer(call -> {
            if (requests.incrementAndGet() == 1) return "[{\"answer\":[{\"id\":\"native:item:42\",\"name\":\"Device\"}]}]";
            throw new IllegalStateException("Count request timed out");
        }).when(adapter).executeDsl(anyString(), anyString(), any(), any());

        JSONObject result = service.getClassDataByPage(page(), "en", permission, "d1");

        assertFailure(result, "Count request timed out");
        assertEquals(2, requests.get());
    }

    @Test
    void classCatalogCountFailuresAreExplicit() throws Exception {
        doThrow(new UnsupportedOperationException("Count exceeds query bound")).when(adapter).executeDsl(anyString(), anyString(), any(), any());

        assertFailure(service.getClassAndCount("en", permission, "d1"), "Count exceeds query bound");
    }

    @Test
    void anEmptyClassHasSuccessfulZeroCount() throws Exception {
        doAnswer(call -> call.<String>getArgument(0).contains("\"function\":\"count\"")
                ? "[{\"answer\":[{\"count_id\":0}]}]" : "[{\"answer\":[]}]")
                .when(adapter).executeDsl(anyString(), anyString(), any(), any());

        JSONObject page = service.getClassDataByPage(page(), "en", permission, "d1");
        JSONObject catalog = service.getClassAndCount("en", permission, "d1");

        assertTrue(page.getBooleanValue("success"), page.toJSONString());
        assertEquals(0, page.getJSONArray("data").size());
        assertEquals(0, page.getIntValue("total_count"));
        assertTrue(catalog.getBooleanValue("success"), catalog.toJSONString());
        assertEquals(0, catalog.getJSONArray("data").getJSONObject(0).getIntValue("count"));
    }

    @Test
    void invalidAndMissingCountsAreFailuresInsteadOfZero() throws Exception {
        for (String response : new String[] {"[{\"answer\":[]}]", "[{\"answer\":[{\"count_id\":\"invalid\"}]}]",
                "[{\"answer\":[{\"count_id\":-1}]}]", "[{\"answer\":[{\"count_id\":1.5}]}]"}) {
            doReturn(response).when(adapter).executeDsl(anyString(), anyString(), any(), any());

            JSONObject result = service.getClassAndCount("en", permission, "d1");

            assertFalse(result.getBooleanValue("success"), result.toJSONString());
            assertTrue(result.getString("message").contains("count"));
            assertNull(result.get("data"));
        }
    }

    @Test
    void detailFailureRetainsTheBackendReasonInsteadOfReportingAMissingObject() throws Exception {
        doThrow(new IllegalStateException("Detail request timed out")).when(adapter).executeDsl(anyString(), anyString(), any(), any());

        assertFailure(service.queryInfoByNode(node(), "en", permission, "d1"), "Detail request timed out");
    }

    @Test
    void namespacedDetailsDecodeOnlyTheClassPrefixAndKeepColonsInStoredIds() throws Exception {
        doReturn("[{\"answer\":[{\"id\":\"native:item:42\",\"name\":\"Device\"}]}]")
                .when(adapter).executeDsl(anyString(), anyString(), any(), any());

        JSONObject result = service.queryInfoByNode(node(), "en", permission, "d1");

        assertTrue(result.getBooleanValue("success"), result.toJSONString());
        ArgumentCaptor<String> executed = ArgumentCaptor.forClass(String.class);
        verify(adapter).executeDsl(executed.capture(), anyString(), any(), any());
        JSONObject object = JSON.parseArray(executed.getValue()).getJSONObject(0).getJSONObject("answer")
                .getJSONArray("steps").getJSONObject(0).getJSONObject("graph")
                .getJSONArray("patterns").getJSONObject(0).getJSONArray("objects").getJSONObject(0);
        assertEquals("/ns/device", object.getString("class"));
        assertEquals("id", object.getJSONObject("conditions").getJSONObject("properties").getString("field"));
        assertEquals("native:item:42", object.getJSONObject("conditions").getJSONObject("properties").getString("value"));
    }

    @Test
    void searchFailureCannotBePresentedAsAnEmptySuccessfulSearch() throws Exception {
        doThrow(new UnsupportedOperationException("Text search is unsupported")).when(adapter).executeDsl(anyString(), anyString(), any(), any());

        assertFailure(service.fullTextSearch(JSONObject.of("term", "Device", "limit", 10), "en", permission, "d1"),
                "Text search is unsupported");
    }

    @Test
    void distinctLookupFailureIsPropagatedToItsCaller() throws Exception {
        doThrow(new IllegalStateException("Distinct request timed out")).when(adapter).executeDsl(anyString(), anyString(), any(), any());

        Exception error = assertThrows(IllegalStateException.class,
                () -> service.queryDistinctAttrValue("/ns/device", "name", null, permission, "d1"));

        assertEquals("Distinct request timed out", error.getMessage());
    }

    private static JSONObject page() {
        return JSONObject.of("classname", "/ns/device", "pagenum", 1, "pagecount", 10);
    }

    private static JSONObject node() {
        return JSONObject.of("class", "/ns/device", "id", "/ns/device:native:item:42");
    }

    private static void assertFailure(JSONObject result, String reason) {
        assertFalse(result.getBooleanValue("success"), result.toJSONString());
        assertEquals(reason, result.getString("message"));
        assertNull(result.get("data"));
        assertNull(result.get("total_count"));
    }
}
