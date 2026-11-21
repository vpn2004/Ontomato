package io.ontomato.dataengine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import io.ontomato.dataengine.bean.PythonExecuteResult;
import io.ontomato.dataengine.bean.abcHarness.ABCHarnessTask;
import io.ontomato.dataengine.bean.abcHarness.ABCHarnessTaskMessage;
import io.ontomato.dataengine.dao.JSONRuleDao;
import io.ontomato.dataengine.logging.AgentCallContext;
import io.ontomato.dataengine.service.ABCHarnessTaskService;
import io.ontomato.dataengine.service.AgentWorkspaceService;
import io.ontomato.dataengine.service.PythonExecuteService;
import io.ontomato.dataengine.service.impl.ABCProgramServiceImpl;
import io.ontomato.dataengine.service.sys.bean.permission.UserDataPermission;

/**
 * The delivered ABC answer carries the partial status of the execution it was taken from: the
 * reused test run when permissions match, or the user's own re-run otherwise, never a mix.
 */
class ABCProgrammerOutputCodePartialResultTest {

	private static final String SESSION = "abc-partial";
	private static final String CODE = "print('program')\n";
	private static final String OUTPUT = "{\"answer\":[{\"total\":5}]}";
	private static final List<Map> OUT_KEY_REFS = List.of(Map.of("key", "total", "className", "/shop/order",
			"attrName", "amount", "asGroupBy", false, "statCal", true));

	@TempDir
	Path workspace;
	private Path file;
	private final ABCProgrammerSkillTools tools = new ABCProgrammerSkillTools();
	private final ABCHarnessTaskService taskService = mock(ABCHarnessTaskService.class);
	private final AgentWorkspaceService workspaceService = mock(AgentWorkspaceService.class);
	private final PythonExecuteService pythonExecuteService = mock(PythonExecuteService.class);
	private final ABCHarnessTask task = new ABCHarnessTask();

	@BeforeEach
	void setUp() throws Exception {
		file = workspace.resolve("answer_0.py");
		Files.writeString(file, CODE);
		JSONRuleDao jsonRuleDao = mock(JSONRuleDao.class);
		JSONObject classDef = JSONObject.of("className", "/shop/order", "attrs", JSONArray.of(JSONObject.of("name", "amount")));
		when(jsonRuleDao.query("domain")).thenReturn(JSONObject.of("classDef", JSONArray.of(classDef)));
		when(workspaceService.resolve(SESSION, "answer_0.py")).thenReturn(file);
		task.setUserId("user-1");
		task.setLang("en");
		task.setTimeoutMinutes(1);
		when(taskService.getTaskBySessionId(SESSION)).thenReturn(task);
		ReflectionTestUtils.setField(tools, "jsonRuleDao", jsonRuleDao);
		ReflectionTestUtils.setField(tools, "abcHarnessTaskService", taskService);
		ReflectionTestUtils.setField(tools, "workspaceService", workspaceService);
		ReflectionTestUtils.setField(tools, "pythonExecuteService", pythonExecuteService);
		ReflectionTestUtils.setField(tools, "abcProgramService", new ABCProgramServiceImpl());
		AgentCallContext.begin(SESSION, "ABC_PROGRAMMER", "domain");
	}

	@AfterEach
	void tearDown() {
		AgentCallContext.end();
	}

	@Test
	void reusedTestRunKeepsItsPartialStatusOnTheDeliveredAnswer() throws Exception {
		UserDataPermission fullData = new UserDataPermission();
		fullData.setFullData(true);
		task.setCurUserDataPermission(fullData);
		testedWith(execution(true));

		assertEquals("Output succeeded", tools.outputCode("total amount", OUT_KEY_REFS, "answer_0.py"));

		JSONObject content = deliveredContent();
		assertEquals(1, content.getJSONArray("data").size());
		assertEquals(Boolean.TRUE, content.getBoolean("truncated"));
		assertEquals(100000, content.getInteger("rowLimit"));
		assertEquals("partial hint", content.getString("truncationHint"));
		verify(pythonExecuteService, never()).runPythonFile(anyString(), any(), anyInt(), anyString(), any(String[].class));
	}

	@Test
	void permissionReRunReportsItsOwnCompleteStatusInsteadOfTheTestRuns() throws Exception {
		UserDataPermission restricted = mock(UserDataPermission.class);
		when(restricted.getRowPermissionMQLByClassName("/shop/order")).thenReturn(JSONObject.of("region", "north"));
		when(restricted.isFullData()).thenReturn(true);
		task.setCurUserDataPermission(restricted);
		testedWith(execution(true));
		PythonExecuteResult rerun = execution(false);
		when(pythonExecuteService.runPythonFile(eq(SESSION), eq(file.toFile()), eq(1), eq("en"), eq("user-1"), eq("domain")))
				.thenReturn(rerun);

		assertEquals("Output succeeded", tools.outputCode("total amount", OUT_KEY_REFS, "answer_0.py"));

		JSONObject content = deliveredContent();
		assertEquals(1, content.getJSONArray("data").size());
		assertFalse(content.containsKey("truncated"));
		assertFalse(content.containsKey("rowLimit"));
		assertFalse(content.containsKey("truncationHint"));
	}

	private void testedWith(PythonExecuteResult tested) {
		when(workspaceService.getExecution(SESSION, file.toString()))
				.thenReturn(new AgentWorkspaceService.FileExecution(CODE, null, tested));
	}

	private static PythonExecuteResult execution(boolean partial) {
		PythonExecuteResult result = new PythonExecuteResult();
		result.setResult(OUTPUT);
		if (partial) {
			result.setPartialQuery(JSONObject.of("truncated", true, "rowLimit", 100000, "truncationHint", "partial hint"));
		}
		return result;
	}

	private JSONObject deliveredContent() {
		ArgumentCaptor<ABCHarnessTaskMessage> message = ArgumentCaptor.forClass(ABCHarnessTaskMessage.class);
		verify(taskService).pushMessage(message.capture());
		assertEquals(ABCHarnessTaskMessage.DATA_TYPE, message.getValue().getType());
		return message.getValue().getContent();
	}
}
