package io.ontomato.dataengine.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import java.util.concurrent.atomic.AtomicInteger;
import io.ontomato.dataengine.logging.DiagnosticEventLogger;
import org.springframework.test.util.ReflectionTestUtils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.ontomato.dataengine.config.BusinessConfig;
import io.ontomato.dataengine.config.DataRagConfig;
import io.ontomato.dataengine.dao.JSONRuleDao;
import io.ontomato.dataengine.dao.VectorResourceDao;
import io.ontomato.dataengine.dataAdapter.DataAdapter;
import io.ontomato.dataengine.dataAdapter.DataAdapterRegistry;
import io.ontomato.dataengine.logging.AgentCallContext;
import io.ontomato.dataengine.service.BusinessConfigService;
import io.ontomato.dataengine.service.AgentWorkspaceService;

/** Exercises the real tool and dispatcher with a counted adapter; no external services. */
class DslSkillToolsFailureBudgetTest {
    @TempDir Path workspaceDirectory;
    private final DslSkillTools tool = new DslSkillTools();
    private final DataAdapter adapter = mock(DataAdapter.class);
    private MockedStatic<DiagnosticEventLogger> events;
    private final AtomicInteger starts = new AtomicInteger();

    @BeforeEach void setup() throws Exception {
        DataRagConfig config = new DataRagConfig();
        BusinessConfigService business = mock(BusinessConfigService.class);
        JSONRuleDao dao = mock(JSONRuleDao.class);
        DataAdapterRegistry registry = mock(DataAdapterRegistry.class);
        Map<String, Object> mapping = Map.of("classDef", List.of(Map.of("className", "/Customer", "attrs",
            List.of(Map.of("name", "id", "type", "varchar", "primaryKey", true)))));
        when(business.get("test-domain")).thenReturn(new BusinessConfig());
        when(dao.query("test-domain")).thenReturn(mapping);
        when(registry.create(any(), any())).thenReturn(adapter);
        when(adapter.consecutiveDslFailureLimit()).thenReturn(4);
        when(adapter.executeDsl(anyString(), anyString(), any(), any()))
            .thenReturn("[{\"answer\":[{\"total\":42}],\"mqls\":[]}]");
        ReflectionTestUtils.setField(tool, "dataRagConfig", config);
        ReflectionTestUtils.setField(tool, "businessConfigService", business);
        ReflectionTestUtils.setField(tool, "jsonRuleDao", dao);
        ReflectionTestUtils.setField(tool, "dataAdapterRegistry", registry);
        ReflectionTestUtils.setField(tool, "vectorResourceDao", mock(VectorResourceDao.class));
        events = mockStatic(DiagnosticEventLogger.class, CALLS_REAL_METHODS);
        events.when(() -> DiagnosticEventLogger.emit(anyString(), any(), any())).thenAnswer(call -> {
            if ("m3.query.started".equals(call.getArgument(0))) starts.incrementAndGet();
            return null;
        });
        AgentCallContext.begin("budget-test", "ABCProgrammer", "test-domain");
    }

    @AfterEach void clear() {
        AgentCallContext.end();
        if (events != null) events.close();
    }

    private JSONObject valid() {
        return JSON.parseObject("""
            {"problem":"Count customers","answer":{"steps":[{"graph":{"patterns":[{"objects":[
              {"idx":0,"variable":"c","class":"/Customer"}]}]},"output":{"to_user":true,
              "fields":[{"variable":"c","field":"id","function":"count","as":"total"}]}}]}}
            """);
    }

    private JSONObject invalid() {
        JSONObject dsl = valid();
        dsl.getJSONObject("answer").getJSONArray("steps").getJSONObject(0).getJSONObject("graph")
            .getJSONArray("patterns").getJSONObject(0).getJSONArray("objects").getJSONObject(0)
            .put("conditions", JSON.parseObject("{\"properties\":[{\"field\":\"id\",\"operator\":\"=\",\"value\":\"x\"}]}"));
        return dsl;
    }

    private long startedEvents() {
        return starts.get();
    }

    @Test void fileEntryPointPersistsValidationAndBudgetFailures() throws Exception {
        AgentWorkspaceService workspace = mock(AgentWorkspaceService.class);
        Path file = workspaceDirectory.resolve("query.json");
        when(workspace.resolve("budget-test", "query.json")).thenReturn(file);
        ReflectionTestUtils.setField(tool, "workspaceService", workspace);
        Files.writeString(file, invalid().toJSONString());
        for (int i = 0; i < 4; i++) {
            assertTrue(tool.executeDslFile("query.json").contains("conditions.properties"));
        }
        Files.writeString(file, valid().toJSONString());
        assertTrue(tool.executeDslFile("query.json").contains("Query result:"));
        var executions = ArgumentCaptor.forClass(AgentWorkspaceService.FileExecution.class);
        verify(workspace, times(5)).clearExecution("budget-test", file.toString());
        verify(workspace, times(5)).saveExecution(eq("budget-test"), eq(file.toString()), executions.capture());
        var saved = executions.getAllValues();
        for (int i = 0; i < 4; i++) {
            assertTrue(saved.get(i).error().contains("conditions.properties"));
        }
        assertNull(saved.get(4).error());
        assertEquals(Files.readString(file), saved.get(4).content());
        assertEquals(0, AgentCallContext.current().getPendingDslTrials());
        assertEquals(1, startedEvents());
        verify(adapter).executeDsl(anyString(), anyString(), any(), any());
    }

    @Test void repeatedInvalidPredicatesAreBlockedButACorrectedPlanCanContinue() throws Exception {
        for (int i = 1; i <= 4; i++) {
            var result = tool.executeDslQuery(invalid());
            assertFalse(result.ok());
            assertTrue(result.error().contains("conditions.properties"));
            assertEquals(i, AgentCallContext.current().getConsecutiveDslFailures());
            assertEquals(0, AgentCallContext.current().getPendingDslTrials());
            assertEquals(i == 4, result.feedback().contains("STOP:"));
        }
        var fifth = tool.executeDslQuery(invalid());
        assertFalse(fifth.ok());
        assertTrue(fifth.error().contains("budget is exhausted"));
        assertEquals(4, AgentCallContext.current().getConsecutiveDslFailures());
        assertEquals(0, startedEvents());
        verify(adapter, never()).executeDsl(anyString(), anyString(), any(), any());
        assertTrue(tool.executeDslQuery(valid()).ok());
        verify(adapter).executeDsl(anyString(), anyString(), any(), any());
    }

    @Test void successResetsFailuresAndANewAgentCallHasAnIndependentBudget() throws Exception {
        for (int i = 0; i < 3; i++) assertFalse(tool.executeDslQuery(invalid()).ok());
        var success = tool.executeDslQuery(valid());
        assertTrue(success.ok(), success.feedback());
        assertEquals(0, AgentCallContext.current().getConsecutiveDslFailures());
        for (int i = 0; i < 4; i++) assertFalse(tool.executeDslQuery(invalid()).ok());
        assertFalse(tool.executeDslQuery(invalid()).ok());
        AgentCallContext.end();
        AgentCallContext.begin("next-session", "ABCProgrammer", "test-domain");
        assertTrue(tool.executeDslQuery(valid()).ok());
        verify(adapter, times(2)).executeDsl(anyString(), anyString(), any(), any());
        assertEquals(2, startedEvents());
    }

    @Test void manyProductiveStepsAndDistinctRepairsAreNotAQuestionRoundLimit() throws Exception {
        for (int i = 0; i < 25; i++) {
            var correction = invalid();
            correction.getJSONObject("answer").getJSONArray("steps").getJSONObject(0)
                .getJSONObject("graph").getJSONArray("patterns").getJSONObject(0)
                .getJSONArray("objects").getJSONObject(0).put("class", "/Repair" + i);
            assertFalse(tool.executeDslQuery(correction).feedback().contains("budget is exhausted"));
        }
        for (int i = 0; i < 25; i++) assertTrue(tool.executeDslQuery(valid()).ok());
        verify(adapter, times(25)).executeDsl(anyString(), anyString(), any(), any());
    }

    @Test void changingOnlyQuestionTextDoesNotEvadeTheRepeatedFailureGuard() {
        for (int i = 0; i < 4; i++) assertFalse(tool.executeDslQuery(invalid()).ok());
        var samePlan = invalid(); samePlan.put("problem", "Cosmetically renamed question");
        assertTrue(tool.executeDslQuery(samePlan).feedback().contains("budget is exhausted"));
    }

    @Test void adapterFailureDoesNotReleaseTheConsumedBudget() throws Exception {
        when(adapter.executeDsl(anyString(), anyString(), any(), any()))
            .thenThrow(new IllegalArgumentException("synthetic unsupported relationship"));
        for (int i = 0; i < 4; i++) assertFalse(tool.executeDslQuery(valid()).ok());
        var fifth = tool.executeDslQuery(valid());
        assertTrue(fifth.error().contains("budget is exhausted"));
        assertEquals(0, AgentCallContext.current().getPendingDslTrials());
        verify(adapter, times(4)).executeDsl(anyString(), anyString(), any(), any());
        assertEquals(4, startedEvents());
    }

    @Test void unlimitedAdaptersKeepTheirExistingRetryBehavior() throws Exception {
        when(adapter.consecutiveDslFailureLimit()).thenReturn(0);
        when(adapter.executeDsl(anyString(), anyString(), any(), any()))
            .thenThrow(new IllegalArgumentException("synthetic failure"));
        for (int i = 0; i < 6; i++) {
            var result = tool.executeDslQuery(valid());
            assertFalse(result.ok());
            assertFalse(result.feedback().contains("budget"));
        }
        verify(adapter, times(6)).executeDsl(anyString(), anyString(), any(), any());
        assertEquals(0, AgentCallContext.current().getConsecutiveDslFailures());
    }
}
