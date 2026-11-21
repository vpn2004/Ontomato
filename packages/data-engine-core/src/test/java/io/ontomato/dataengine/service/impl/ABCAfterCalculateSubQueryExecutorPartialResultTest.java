package io.ontomato.dataengine.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import io.ontomato.dataengine.bean.AfterCalculatorResult;
import io.ontomato.dataengine.bean.abcQuestion.ABCSubQueryTask;
import io.ontomato.dataengine.config.DataRagConfig;
import io.ontomato.dataengine.dao.ABCSubQueryTaskDao;
import io.ontomato.dataengine.service.AfterCalculateService;
import io.ontomato.dataengine.service.BackendSessionEntrance;
import io.ontomato.dataengine.service.CheckService;
import io.ontomato.dataengine.service.PythonCalculatorService;
import io.ontomato.dataengine.service.SessionRegistryService;

/**
 * The standard-path after-calculation reads its dependencies' cached rows, which come from their
 * stored row-permission answers; a partial dependency must mark the calculated answer, including the
 * "no matching data" answer, while a failed dependency stays a plain failure.
 */
class ABCAfterCalculateSubQueryExecutorPartialResultTest {

	private static final String SESSION = "after-calc-partial";

	@BeforeAll
	static void publishSessionEntrance() {
		BackendSessionEntrance entrance = new BackendSessionEntrance();
		ReflectionTestUtils.setField(entrance, "sessionRegistryService", mock(SessionRegistryService.class));
		entrance.publishInstance();
	}

	@Test
	void calculatedAnswerFromAPartialDependencyIsPartial() {
		JSONObject answer = runAfterCalculation(dependency(0, true, true), dependency(1, false, true));

		assertEquals(1, answer.getJSONArray("answer").size());
		assertEquals(Boolean.TRUE, answer.getBoolean("truncated"));
		assertEquals(100000, answer.getInteger("rowLimit"));
		assertEquals("dependency 0 hint", answer.getString("truncationHint"));
	}

	@Test
	void noMatchingDataFromAPartialDependencyIsPartial() {
		JSONObject answer = runAfterCalculation(dependency(0, true, false));

		assertTrue(answer.getJSONArray("answer").getJSONObject(0).getString("result").contains("full result is unknown"));
		assertEquals(Boolean.TRUE, answer.getBoolean("truncated"));
	}

	@Test
	void completeDependenciesLeaveTheAnswerUnmarked() {
		JSONObject answer = runAfterCalculation(dependency(0, false, true), dependency(1, false, true));

		assertFalse(answer.containsKey("truncated"));
		assertFalse(answer.containsKey("rowLimit"));
		assertFalse(answer.containsKey("truncationHint"));
	}

	@Test
	void aFailedDependencyStaysAFailureWithoutPartialFields() {
		ABCSubQueryTask failed = dependency(1, false, true);
		Map<String, Object> schemaDef = new HashMap<>();
		schemaDef.put("error", "DSL execution error");
		failed.setCacheSchemaDef(schemaDef);

		JSONObject answer = runAfterCalculation(dependency(0, true, true), failed);

		assertEquals("Dependent sub-query execution failed", answer.getJSONArray("answer").getJSONObject(0).getString("result"));
		assertFalse(answer.containsKey("truncated"));
	}

	@Test
	void windowDependencyKeepsItsSourceRange() {
		ABCSubQueryTask source = dependency(0, false, true);
		source.getRowPermissionDataMap().getJSONArray("data").getJSONObject(0).put("resultWindow", JSONObject.of(
			"contract", "onn_binding_window_v1", "answerComplete", true, "sourceExhausted", false,
			"matchingTotal", 262144, "totalExact", true, "offset", 0, "count", 5000, "returnedRows", 5000,
			"consistency", "version_checked", "dataVersion", "boot:1"));
		JSONObject result = runAfterCalculation(source);
		assertEquals(262144, result.getJSONArray("sourceWindows").getJSONObject(0).getIntValue("matchingTotal"));
		assertFalse(result.containsKey("truncated"));
	}

	/** Runs the executor over stored dependencies and returns the answer object it persisted. */
	private static JSONObject runAfterCalculation(ABCSubQueryTask... dependencies) {
		ABCSubQueryTask afterTask = new ABCSubQueryTask();
		afterTask.setSessionId(SESSION);
		afterTask.setType(ABCSubQueryTask.AFTER_CALCULATE_TYPE);
		afterTask.setIndex(dependencies.length);
		afterTask.setSubQuery(JSONObject.of("subQuestion", "sum the orders"));
		List<ABCSubQueryTask> tasks = new java.util.ArrayList<>(List.of(dependencies));
		tasks.add(afterTask);
		ABCSubQueryTaskDao dao = mock(ABCSubQueryTaskDao.class);
		when(dao.queryBySessionId(SESSION, false, false)).thenReturn(tasks);
		AfterCalculateService afterCalculateService = mock(AfterCalculateService.class);
		AfterCalculatorResult result = new AfterCalculatorResult();
		result.setAnswer(List.of(Map.of("total", 7)));
		result.setLogic("sum");
		when(afterCalculateService.calculate(anyString(), anyString(), anyList(), anyString(), any(), any())).thenReturn(result);
		DataRagConfig config = new DataRagConfig();
		config.setCardBaseUrl("http://cards");

		new ABCAfterCalculateSubQueryExecutor(afterTask, afterCalculateService, mock(PythonCalculatorService.class),
				mock(CheckService.class), dao, config, "en", "domain").run();

		assertEquals(afterTask.getDataMap(), afterTask.getRowPermissionDataMap());
		JSONArray data = afterTask.getDataMap().getJSONArray("data");
		assertEquals(1, data.size());
		return data.getJSONObject(0);
	}

	/** A finished DSL dependency as the task DAO returns it: row ids in answer, answer-level flags kept. */
	private static ABCSubQueryTask dependency(int index, boolean partial, boolean hasRows) {
		ABCSubQueryTask task = new ABCSubQueryTask();
		task.setSessionId(SESSION);
		task.setType(ABCSubQueryTask.DSL_TYPE);
		task.setIndex(index);
		task.setSubQuery(JSONObject.of("subQuestion", "orders " + index));
		JSONObject datum = JSONObject.of("answer", JSONArray.of("rows-" + index + "-0"));
		if (partial) {
			datum.put("truncated", true);
			datum.put("rowLimit", 100000);
			datum.put("truncationHint", "dependency " + index + " hint");
		}
		task.setDataMap(JSONObject.of("data", JSONArray.of(JSONObject.of("answer", JSONArray.of("admin-" + index)))));
		task.setRowPermissionDataMap(JSONObject.of("data", JSONArray.of(datum)));
		Map<String, Object> schemaDef = new HashMap<>();
		schemaDef.put("defs", hasRows ? JSONArray.of(JSONObject.of("keyname", "amount")) : JSONArray.of());
		schemaDef.put("filePath", "/tmp/cache-" + index + ".json");
		schemaDef.put("fileName", "cache-" + index + ".json");
		schemaDef.put("cacheSamples", JSONArray.of());
		schemaDef.put("cacheDataCount", hasRows ? 1 : 0);
		schemaDef.put("dslConsanguinityMap", new HashMap<>());
		task.setCacheSchemaDef(schemaDef);
		return task;
	}
}
