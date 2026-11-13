package io.ontomato.dataengine.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import io.ontomato.dataengine.dao.JSONRuleDao;
import io.ontomato.dataengine.dataAdapter.DataAdapter;
import io.ontomato.dataengine.dataAdapter.DataAdapterRegistry;
import io.ontomato.dataengine.logging.AgentCallContext;
import io.ontomato.dataengine.service.BusinessConfigService;

class ABCProgrammerPlanningRulesTest {
    @Test void preparationUsesOneDomainSnapshotAndIncludesOnlyRequestedClassesAndDirectEdges() {
        var tools = new ABCProgrammerSkillTools();
        var dao = mock(JSONRuleDao.class);
        var registry = mock(DataAdapterRegistry.class);
        var configs = mock(BusinessConfigService.class);
        var adapter = mock(DataAdapter.class);
        JSONObject ontology = JSONObject.parseObject("""
            {"datasetdesc":"Sample","classDef":[
              {"className":"/Order","attrs":[{"name":"customer","type":"varchar","attrDesc":"Customer code"}]},
              {"className":"/Customer","attrs":[]},
              {"className":"/PrivateOtherDomain","attrs":[]}],
              "relationship_rule":{"/placed_by":{"fromclass":"/Order","toclass":"/Customer","desc":"Placed by"}}}
            """);
        when(dao.query("domain")).thenReturn(ontology);
        when(registry.create(null, ontology)).thenReturn(adapter);
        when(adapter.queryPlanningRules()).thenReturn("SOURCE-RULES");
        when(adapter.queryPlanningExample(any())).thenReturn("SOURCE-EXAMPLE");
        ReflectionTestUtils.setField(tools, "jsonRuleDao", dao);
        ReflectionTestUtils.setField(tools, "businessConfigService", configs);
        ReflectionTestUtils.setField(tools, "dataAdapterRegistry", registry);
        AgentCallContext.begin("context-test", "ABC_PROGRAMMER", "domain");
        try {
            String context = ReflectionTestUtils.invokeMethod(tools, "getQueryContext", List.of("/Order"));
            assertTrue(context.contains("Customer code"));
            assertTrue(context.contains("/placed_by"));
            assertTrue(context.contains("/Customer"), "Include adjacent definitions for further exploration");
            assertTrue(context.contains("SOURCE-EXAMPLE"));
            assertEquals(1, context.split("SOURCE-RULES", -1).length - 1);
            assertFalse(context.contains("/PrivateOtherDomain"));
            verify(dao).query("domain");
            verify(registry).create(null, ontology);
            String empty = ReflectionTestUtils.invokeMethod(tools, "getQueryContext", List.of());
            assertTrue(empty.startsWith("Failed"));
            assertFalse(empty.contains("PrivateOtherDomain"));
        } finally { AgentCallContext.end(); }
    }

    @Test void syntaxAndExampleUseTheCurrentDomainAdapterWithoutLeakingRulesToOtherSources() throws Exception {
        var tools = new ABCProgrammerSkillTools();
        var dao = mock(JSONRuleDao.class);
        var registry = mock(DataAdapterRegistry.class);
        var configs = mock(BusinessConfigService.class);
        var adapter = mock(DataAdapter.class);
        JSONObject ontology = JSONObject.of("classDef", JSONArray.of(JSONObject.of("className", "/test/item", "attrs",
            JSONArray.of(JSONObject.of("name", "id", "type", "varchar", "primaryKey", true)))), "relationship_rule", new JSONObject());
        when(dao.query("domain")).thenReturn(ontology);
        when(registry.create(null, ontology)).thenReturn(adapter);
        when(adapter.queryPlanningRules()).thenReturn("SOURCE-SPECIFIC-PLAN-RULES");
        ReflectionTestUtils.setField(tools, "jsonRuleDao", dao);
        ReflectionTestUtils.setField(tools, "businessConfigService", configs);
        ReflectionTestUtils.setField(tools, "dataAdapterRegistry", registry);
        AgentCallContext.begin("planning-test", "ABC_PROGRAMMER", "domain");
        try {
            String syntax = tools.getDslSyntax(List.of("/test/item"));
            assertFalse(syntax.startsWith("Failed"), syntax);
            assertTrue(syntax.endsWith("SOURCE-SPECIFIC-PLAN-RULES"), syntax);
            String example = tools.getDslExample(List.of("/test/item"));
            assertFalse(example.startsWith("Failed"), example);
            assertTrue(example.endsWith("SOURCE-SPECIFIC-PLAN-RULES"), example);
            when(adapter.queryPlanningExample(List.of("/test/item"))).thenReturn("COMPATIBLE-SOURCE-EXAMPLE");
            assertTrue(tools.getDslExample(List.of("/test/item")).startsWith("COMPATIBLE-SOURCE-EXAMPLE"));
            when(adapter.queryPlanningRules()).thenReturn("");
            assertFalse(tools.getDslSyntax(List.of("/test/item")).contains("SOURCE-SPECIFIC"));
            verify(configs, times(4)).get("domain");
        } finally { AgentCallContext.end(); }
    }
}
