package io.ontomato.dataengine.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.ontomato.dataengine.config.BusinessConfig;
import io.ontomato.dataengine.core.bean.User;
import io.ontomato.dataengine.dao.ActionDao;
import io.ontomato.dataengine.dao.JSONRuleDao;
import io.ontomato.dataengine.dao.VectorResourceDao;
import io.ontomato.dataengine.dataAdapter.DataAdapter;
import io.ontomato.dataengine.dataAdapter.DataAdapterCapabilities;
import io.ontomato.dataengine.dataAdapter.DataAdapterRegistry;
import io.ontomato.dataengine.service.ABCProgramService;
import io.ontomato.dataengine.service.AdminService;
import io.ontomato.dataengine.service.AgentWorkspaceService;
import io.ontomato.dataengine.service.BusinessConfigService;
import io.ontomato.dataengine.service.SandboxService;
import io.ontomato.dataengine.service.sys.bean.permission.UserDataPermission;

/** A disabled backend write must be refused before local files, vectors, code or sandbox state are changed. */
class ReadOnlyAdapterWriteBoundariesTest {
    private final DataAdapter adapter = mock(DataAdapter.class, CALLS_REAL_METHODS);
    private final DataAdapterRegistry registry = mock(DataAdapterRegistry.class);
    private final BusinessConfigService configs = mock(BusinessConfigService.class);
    private final JSONRuleDao rules = mock(JSONRuleDao.class);
    private final VectorResourceDao vectors = mock(VectorResourceDao.class);
    private final WriteFunctionOperationServiceImpl writes = new WriteFunctionOperationServiceImpl();

    @BeforeEach
    void wire() {
        BusinessConfig config = new BusinessConfig();
        when(configs.get("d1")).thenReturn(config);
        when(registry.create(eq(config), any())).thenReturn(adapter);
        doReturn(DataAdapterCapabilities.onnReadOnly()).when(adapter).capabilities();
        ReflectionTestUtils.setField(writes, "businessConfigService", configs);
        ReflectionTestUtils.setField(writes, "dataAdapterRegistry", registry);
        ReflectionTestUtils.setField(writes, "jsonRuleDao", rules);
        ReflectionTestUtils.setField(writes, "vectorResourceDao", vectors);
    }

    @Test
    void objectAndAttachmentWritesAreRejectedBeforeLoadingRulesOrPersistingAnything() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> writes.insert("Asset", new JSONArray(), "", "d1"));
        assertThrows(UnsupportedOperationException.class, () -> writes.update("Asset", new JSONObject(), new JSONObject(), "", "d1"));
        assertThrows(UnsupportedOperationException.class, () -> writes.delete("Asset", new JSONObject(), "", "d1"));
        assertThrows(UnsupportedOperationException.class,
                () -> writes.appendVector("Asset", "files", "a1", "content", null, "txt", "", "d1"));
        assertThrows(UnsupportedOperationException.class,
                () -> writes.updateVector("Asset", "files", "a1", "f1", "content", null, "", "d1"));
        assertThrows(UnsupportedOperationException.class,
                () -> writes.deleteVector("Asset", "files", "a1", "f1", "", "d1"));

        verifyNoInteractions(rules, vectors);
        verify(adapter, never()).insertObjects(any(), any(), any());
        verify(adapter, never()).updateObjects(any(), any(), any(), any());
        verify(adapter, never()).deleteObjects(any(), any(), any());
        verify(adapter, never()).queryVectorAttr(any(), any(), any());
    }

    @Test
    void edgeWritesAreRejectedBeforeLoadingRelationships() throws Exception {
        assertThrows(UnsupportedOperationException.class,
                () -> writes.createEdge("installedAt", "Asset", "a1", "Site", "s1", "", "d1"));
        assertThrows(UnsupportedOperationException.class,
                () -> writes.deleteEdge("installedAt", "Asset", "a1", "Site", "s1", "", "d1"));

        verifyNoInteractions(rules, vectors);
        verify(adapter, never()).createEdge(any(), any(), any(), any(), any(), any());
        verify(adapter, never()).deleteEdge(any(), any(), any(), any(), any(), any());
    }

    @Test
    void sandboxRestrictionAppliesEvenWhenObjectWritesAreSupported() throws Exception {
        doReturn(new DataAdapterCapabilities(true, true, true, true, true, false, false)).when(adapter).capabilities();
        assertThrows(UnsupportedOperationException.class, () -> writes.insert("Asset", new JSONArray(), "sandbox1", "d1"));
        assertThrows(UnsupportedOperationException.class,
                () -> writes.createEdge("installedAt", "Asset", "a1", "Site", "s1", "sandbox1", "d1"));
        verifyNoInteractions(rules, vectors);
        verify(adapter, never()).insertObjects(any(), any(), any());
        verify(adapter, never()).createEdge(any(), any(), any(), any(), any(), any());
    }

    @Test
    void sandboxLifecycleCannotTouchBackendOrVectorTables() throws Exception {
        SandboxServiceImpl sandbox = new SandboxServiceImpl();
        ReflectionTestUtils.setField(sandbox, "businessConfigService", configs);
        ReflectionTestUtils.setField(sandbox, "dataAdapterRegistry", registry);
        ReflectionTestUtils.setField(sandbox, "vectorResourceDao", vectors);
        Map<String, Object> classDef = Map.of("className", "Asset", "attrs", List.of(Map.of("name", "files", "type", "vector")));

        assertThrows(UnsupportedOperationException.class, () -> sandbox.createNamespace("sandbox1", "d1"));
        assertThrows(UnsupportedOperationException.class, () -> sandbox.truncateNamespace("sandbox1", "d1"));
        assertThrows(UnsupportedOperationException.class, () -> sandbox.dropNamespace("sandbox1", "d1"));
        assertThrows(UnsupportedOperationException.class, () -> sandbox.createClass("sandbox1", classDef, "d1"));
        assertThrows(UnsupportedOperationException.class, () -> sandbox.createEdge("sandbox1", "installedAt", "d1"));
        assertThrows(UnsupportedOperationException.class, () -> sandbox.insertObjects("sandbox1", classDef, null, "d1"));
        assertThrows(UnsupportedOperationException.class, () -> sandbox.insertRelations("sandbox1", null, "d1"));

        verifyNoInteractions(vectors);
        verify(adapter, never()).createNamespace(any());
        verify(adapter, never()).truncateNamespace(any());
        verify(adapter, never()).dropNamespace(any());
        verify(adapter, never()).createClass(any(), any());
        verify(adapter, never()).createEdgeType(any(), any());
        verify(adapter, never()).insertObjects(any(), any(), any());
    }

    @Test
    void actionGenerationAndExecutionStopBeforeSandboxWorkspaceOrCodeExecution() {
        ActionServiceImpl actions = new ActionServiceImpl();
        AdminService admin = mock(AdminService.class);
        SandboxService sandbox = mock(SandboxService.class);
        AgentWorkspaceService workspaces = mock(AgentWorkspaceService.class);
        ActionDao actionDao = mock(ActionDao.class);
        ABCProgramService programs = mock(ABCProgramService.class);
        ReflectionTestUtils.setField(actions, "businessConfigService", configs);
        ReflectionTestUtils.setField(actions, "dataAdapterRegistry", registry);
        ReflectionTestUtils.setField(actions, "adminService", admin);
        ReflectionTestUtils.setField(actions, "sandboxService", sandbox);
        ReflectionTestUtils.setField(actions, "agentWorkspaceService", workspaces);
        ReflectionTestUtils.setField(actions, "actionDao", actionDao);
        ReflectionTestUtils.setField(actions, "abcProgramService", programs);
        User user = mock(User.class);
        when(user.getDomainId()).thenReturn("d1");

        assertThrows(UnsupportedOperationException.class, () -> actions.generateFromNatureLanguage("Create asset", "en", "d1", true));
        assertThrows(UnsupportedOperationException.class,
                () -> actions.execute("action1", new JSONObject(), "en", user, mock(UserDataPermission.class), 1));

        verifyNoInteractions(admin, sandbox, workspaces, actionDao, programs);
    }
}
