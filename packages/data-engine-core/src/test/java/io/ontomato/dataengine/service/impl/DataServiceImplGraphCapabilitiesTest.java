package io.ontomato.dataengine.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.ontomato.dataengine.config.BusinessConfig;
import io.ontomato.dataengine.dataAdapter.DataAdapter;
import io.ontomato.dataengine.dataAdapter.DataAdapterCapabilities;
import io.ontomato.dataengine.dataAdapter.DataAdapterRegistry;
import io.ontomato.dataengine.service.BusinessConfigService;
import io.ontomato.dataengine.service.LangService;

class DataServiceImplGraphCapabilitiesTest {
    private final DataServiceImpl service = new DataServiceImpl();
    private final DataAdapter adapter = mock(DataAdapter.class, CALLS_REAL_METHODS);

    @BeforeEach
    void wire() {
        BusinessConfig config = new BusinessConfig();
        config.setDataAdapter("native");
        BusinessConfigService configs = mock(BusinessConfigService.class);
        when(configs.get("d1")).thenReturn(config);
        DataAdapterRegistry registry = mock(DataAdapterRegistry.class);
        when(registry.create(eq(config), any())).thenReturn(adapter);
        LangService lang = mock(LangService.class);
        when(lang.get(any(), anyString())).thenAnswer(call -> call.getArgument(1));
        ReflectionTestUtils.setField(service, "businessConfigService", configs);
        ReflectionTestUtils.setField(service, "dataAdapterRegistry", registry);
        ReflectionTestUtils.setField(service, "langService", lang);
    }

    @Test
    void nativeNeighborQueriesRetainRelationAndEdgeIdentityAndColonsInObjectIds() throws Exception {
        doReturn(new DataAdapterCapabilities(true, true, false, false, false, false, true)).when(adapter).capabilities();
        JSONObject graph = JSON.parseObject("""
                {"nodes":[{"id":"Site:s:2","class":"Site","name":"Site 2"}],
                 "edges":[
                   {"id":"edge:1","relation":"installedAt","source":"Asset:a:1","target":"Site:s:2"},
                   {"id":"edge:2","relation":"maintainedBy","source":"Asset:a:1","target":"Site:s:2"}]}
                """);
        doReturn(graph).when(adapter).queryNeighbors("Asset", "a:1");

        JSONObject result = service.queryNextByNode(JSON.parseObject("{\"class\":\"Asset\",\"id\":\"Asset:a:1\"}"), "en", "d1");

        assertEquals(true, result.getBoolean("success"));
        assertSame(graph, result.get("data"));
        assertEquals(2, result.getJSONObject("data").getJSONArray("edges").size());
        assertEquals("edge:2", result.getJSONObject("data").getJSONArray("edges").getJSONObject(1).getString("id"));
        verify(adapter).queryNeighbors("Asset", "a:1");
    }

    @Test
    void disabledGraphReadsDoNotReachTheBackend() throws Exception {
        doReturn(DataAdapterCapabilities.onnReadOnly()).when(adapter).capabilities();

        JSONObject result = service.queryNextByNode(JSON.parseObject("{\"class\":\"Asset\",\"id\":\"Asset:a:1\"}"), "en", "d1");

        assertEquals(false, result.getBoolean("success"));
        verify(adapter, never()).queryNeighbors(anyString(), anyString());
        verify(adapter, never()).decodeNodeReference(anyString(), anyString());
    }

    @Test
    void nodeReferencesFromAnotherClassAreRejectedBeforeNeighborReads() throws Exception {
        doReturn(new DataAdapterCapabilities(true, true, false, false, false, false, true)).when(adapter).capabilities();

        JSONObject result = service.queryNextByNode(JSON.parseObject("{\"class\":\"Asset\",\"id\":\"Site:a:1\"}"), "en", "d1");

        assertEquals(false, result.getBoolean("success"));
        verify(adapter, never()).queryNeighbors(anyString(), anyString());
    }
}
