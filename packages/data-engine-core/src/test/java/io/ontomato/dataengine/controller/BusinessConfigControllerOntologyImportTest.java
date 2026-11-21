package io.ontomato.dataengine.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.ontomato.dataengine.config.BusinessConfig;
import io.ontomato.dataengine.core.bean.User;
import io.ontomato.dataengine.dataAdapter.DataAdapter;
import io.ontomato.dataengine.dataAdapter.DataAdapterCapabilities;
import io.ontomato.dataengine.dataAdapter.DataAdapterConnection;
import io.ontomato.dataengine.dataAdapter.DataAdapterRegistry;
import io.ontomato.dataengine.service.AdminService;
import io.ontomato.dataengine.service.BusinessConfigService;
import io.ontomato.dataengine.util.SystemUtils;

class BusinessConfigControllerOntologyImportTest {
    private static final String SCHEMA = """
            {"datasetdesc":"Backend description","classlist":["Asset","Site"],"nativeVersion":"v1",
             "classDef":[
               {"className":"Asset","showName":"Asset","classDesc":"Backend asset description","nativeClass":"asset",
                "attrs":[{"name":"id","type":"varchar","primaryKey":true,"enable":true,"sourceName":"@identity","nativeAttribute":"object-id"},
                         {"name":"code","type":"varchar","showName":"Code","attrDesc":"Backend code description","enable":true,"sourceName":"code"}]},
               {"className":"Site","attrs":[{"name":"id","type":"varchar","primaryKey":true,"enable":true,"sourceName":"@identity"}]}],
             "relationship_rule":{"installedAt":{"fromclass":"Asset","toclass":"Site","desc":"Installed at","fromField":"id","toField":"id"}}}
            """;

    private final BusinessConfigController controller = new BusinessConfigController();
    private final BusinessConfig config = new BusinessConfig();
    private final BusinessConfigService configs = mock(BusinessConfigService.class);
    private final DataAdapter adapter = mock(DataAdapter.class, CALLS_REAL_METHODS);
    private final DataAdapterRegistry registry = mock(DataAdapterRegistry.class);
    private final AdminService admin = mock(AdminService.class);
    private MockedStatic<SystemUtils> identity;
    private JSONObject schema;

    @BeforeEach
    void wire() throws Exception {
        config.setDataAdapter("onn-core");
        DataAdapterConnection connection = new DataAdapterConnection();
        connection.setUrl("http://saved-source/onn");
        connection.setUser("fixture-user");
        connection.setPassword("fixture-password");
        config.getDataAdapterConnections().put("onn-core", connection);
        when(configs.get("d1")).thenReturn(config);
        when(registry.create(eq(config), any())).thenReturn(adapter);
        doReturn(DataAdapterCapabilities.onnReadOnly()).when(adapter).capabilities();
        schema = JSON.parseObject(SCHEMA);
        doReturn(schema).when(adapter).readOntology();
        ReflectionTestUtils.setField(controller, "businessConfigService", configs);
        ReflectionTestUtils.setField(controller, "dataAdapterRegistry", registry);
        ReflectionTestUtils.setField(controller, "adminService", admin);
        User user = mock(User.class);
        when(user.getDomainId()).thenReturn("d1");
        identity = mockStatic(SystemUtils.class);
        identity.when(SystemUtils::getCurUser).thenReturn(user);
    }

    @AfterEach
    void release() {
        identity.close();
    }

    @Test
    void previewReadsOnlyTheSavedSourceAndReturnsTheOntologyWithoutConnectionSecrets() throws Exception {
        JSONObject result = controller.adapterOntology();

        assertTrue(result.getBooleanValue("success"), result.toJSONString());
        JSONObject data = result.getJSONObject("data");
        assertEquals("onn-core", data.getString("type"));
        assertEquals(64, data.getString("connectionFingerprint").length());
        JSONObject pretty = data.getJSONObject("ontology");
        assertEquals("Backend description", pretty.getString("datasetDesc"));
        assertEquals("Asset", pretty.getJSONArray("classDefs").getJSONObject(0).getString("name"));
        assertEquals("varchar", pretty.getJSONArray("classDefs").getJSONObject(0).getJSONArray("attrs").getJSONObject(0).getString("type"));
        assertEquals("@identity", pretty.getJSONArray("classDefs").getJSONObject(0).getJSONArray("attrs").getJSONObject(0).getString("sourceName"));
        assertEquals("code", pretty.getJSONArray("classDefs").getJSONObject(0).getJSONArray("attrs").getJSONObject(1).getString("sourceName"));
        assertEquals("Site", pretty.getJSONObject("relationshipDefs").getJSONObject("installedAt").getString("toClass"));
        assertFalse(result.toJSONString().contains("fixture-password"));
        assertFalse(result.toJSONString().contains("http://saved-source"));
        verify(registry).create(config, null);
        verify(adapter).capabilities();
        verify(adapter).readOntology();
        verifyNoMoreInteractions(adapter);
        verifyNoInteractions(admin);
        verify(configs, never()).set(any(), anyString());
    }

    @Test
    void successfulImportPassesTheCompleteReviewedBackendModelToAdminWithoutMutatingTheSource() throws Exception {
        JSONObject request = preview();
        String originalSchema = schema.toJSONString();

        JSONObject result = controller.importAdapterOntology(request);

        assertTrue(result.getBooleanValue("success"), result.toJSONString());
        ArgumentCaptor<JSONObject> imported = ArgumentCaptor.forClass(JSONObject.class);
        verify(admin).setPrettyJSONRule(imported.capture(), eq("d1"));
        assertEquals(request.getJSONObject("ontology"), imported.getValue());
        assertEquals(imported.getValue(), result.getJSONObject("data"));
        assertEquals("Backend asset description", imported.getValue().getJSONArray("classDefs").getJSONObject(0).getString("desc"));
        assertEquals("@identity", imported.getValue().getJSONArray("classDefs").getJSONObject(0).getJSONArray("attrs").getJSONObject(0).getString("sourceName"));
        assertEquals(originalSchema, schema.toJSONString());
        verify(admin, never()).getPrettyJSONRule(anyString());
        verify(adapter, times(2)).capabilities();
        verify(adapter, times(2)).readOntology();
        verifyNoMoreInteractions(adapter);
        verify(configs, never()).set(any(), anyString());
    }

    @Test
    void bucketIndicatorsSurvivePreviewAndImport() throws Exception {
        JSONObject metrics = JSON.parseObject("""
                {"name":"metrics","type":"bucket","enable":true,"sourceName":"metrics",
                 "indicators":[{"name":"temperature","desc":"Temperature","unit":"C"}]}
                """);
        schema.getJSONArray("classDef").getJSONObject(0).getJSONArray("attrs").add(metrics);
        JSONObject request = preview();
        JSONObject reviewed = request.getJSONObject("ontology").getJSONArray("classDefs")
                .getJSONObject(0).getJSONArray("attrs").getJSONObject(2);

        JSONObject result = controller.importAdapterOntology(request);

        assertTrue(result.getBooleanValue("success"), result.toJSONString());
        assertEquals(metrics.getJSONArray("indicators"), reviewed.getJSONArray("indicators"));
        verify(admin).setPrettyJSONRule(eq(request.getJSONObject("ontology")), eq("d1"));
    }

    @Test
    void aChangedSavedConnectionIsRefusedBeforeReadingOrImportingAnything() throws Exception {
        JSONObject request = preview();
        clearInvocations(adapter, registry);
        config.getDataAdapterConnections().get("onn-core").setPassword("new-fixture-password");

        JSONObject result = controller.importAdapterOntology(request);

        assertFalse(result.getBooleanValue("success"));
        assertTrue(result.getString("message").contains("Saved data source changed"));
        verifyNoInteractions(adapter, registry, admin);
        verify(configs, never()).set(any(), anyString());
    }

    @Test
    void aChangedSelectedAdapterIsRefusedBeforeBackendCalls() {
        JSONObject request = preview();
        clearInvocations(adapter, registry);
        config.setDataAdapter("postgresql");

        JSONObject result = controller.importAdapterOntology(request);

        assertFalse(result.getBooleanValue("success"));
        verifyNoInteractions(adapter, registry, admin);
    }

    @Test
    void backendSchemaChangesAndModifiedReviewedDefinitionsCannotBeImported() throws Exception {
        JSONObject request = preview();
        schema.getJSONArray("classDef").getJSONObject(0).getJSONArray("attrs").getJSONObject(1).put("type", "text");

        JSONObject changedBackend = controller.importAdapterOntology(request);

        assertFalse(changedBackend.getBooleanValue("success"));
        assertTrue(changedBackend.getString("message").contains("Backend schema changed"));
        verifyNoInteractions(admin);

        JSONObject reviewed = preview();
        reviewed.getJSONObject("ontology").getJSONArray("classDefs").getJSONObject(0).put("desc", "Modified since preview");
        JSONObject changedReview = controller.importAdapterOntology(reviewed);

        assertFalse(changedReview.getBooleanValue("success"));
        verifyNoInteractions(admin);
    }

    @Test
    void missingOrModifiedSourceMappingsCannotBeImported() {
        for (String mapping : new String[] {null, "id"}) {
            JSONObject request = preview();
            JSONObject id = request.getJSONObject("ontology").getJSONArray("classDefs")
                    .getJSONObject(0).getJSONArray("attrs").getJSONObject(0);
            if (mapping == null) id.remove("sourceName");
            else id.put("sourceName", mapping);

            JSONObject result = controller.importAdapterOntology(request);

            assertFalse(result.getBooleanValue("success"));
            verifyNoInteractions(admin);
        }
    }

    @Test
    void unsupportedSchemaReadsAndAnUnavailableSourceNeverReachAdmin() throws Exception {
        doReturn(DataAdapterCapabilities.legacy()).when(adapter).capabilities();

        JSONObject unsupported = controller.adapterOntology();

        assertFalse(unsupported.getBooleanValue("success"));
        verify(adapter, never()).readOntology();
        verifyNoInteractions(admin);

        doReturn(DataAdapterCapabilities.onnReadOnly()).when(adapter).capabilities();
        doThrow(new IllegalStateException("source unavailable")).when(adapter).readOntology();

        JSONObject unavailable = controller.adapterOntology();

        assertFalse(unavailable.getBooleanValue("success"));
        assertEquals("source unavailable", unavailable.getString("message"));
        verifyNoInteractions(admin);
    }

    @Test
    void adminRevalidationFailureIsReturnedWithoutBackendWrites() throws Exception {
        JSONObject request = preview();
        doThrow(new UnsupportedOperationException("Schema changed during import")).when(admin).setPrettyJSONRule(any(), eq("d1"));

        JSONObject result = controller.importAdapterOntology(request);

        assertFalse(result.getBooleanValue("success"));
        assertEquals("Schema changed during import", result.getString("message"));
        verify(adapter, times(2)).capabilities();
        verify(adapter, times(2)).readOntology();
        verifyNoMoreInteractions(adapter);
    }

    private JSONObject preview() {
        JSONObject result = controller.adapterOntology();
        assertTrue(result.getBooleanValue("success"), result.toJSONString());
        return JSON.parseObject(result.getJSONObject("data").toJSONString());
    }
}
