package io.ontomato.dataengine.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import io.ontomato.dataengine.bean.PythonExecuteResult;
import io.ontomato.dataengine.config.DataRagConfig;
import io.ontomato.dataengine.util.DslToPythonCodeUtil;

/**
 * Runs real Python programs against a local stand-in for the engine's {@code /dsl/execute}: the
 * partial status of a query must reach {@link PythonExecuteResult} without the program printing it,
 * also when the program reduces the rows to a single statistic, and must not leak into the next run.
 * Only answers decoded from this engine's own origin and path count.
 */
class PythonExecuteServiceImplQueryStatusTest {

	private static final String HINT = "Stopped after 100000 intermediate rows; only the processed portion is included.";
	private static HttpServer server;
	private static HttpServer foreignServer;
	private static PythonExecuteServiceImpl service;
	@TempDir
	Path workspace;

	@BeforeAll
	static void startEngineStandIn() throws Exception {
		String python = System.getenv().getOrDefault("PYTHON", "python3");
		Assumptions.assumeTrue(pythonHasRequests(python), "python with the requests package is required");
		server = standIn();
		foreignServer = standIn();
		DataRagConfig config = new DataRagConfig();
		config.setPythonpath(python);
		service = new PythonExecuteServiceImpl();
		ReflectionTestUtils.setField(service, "dataRagConfig", config);
		ReflectionTestUtils.setField(service, "serverPort", server.getAddress().getPort());
	}

	/** Serves the engine's answer shape on /dsl/execute and, as an unrelated endpoint, on /other/execute. */
	private static HttpServer standIn() throws IOException {
		HttpServer standIn = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		HttpHandler handler = exchange -> {
			JSONObject request = JSONObject.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			String mode = request.getJSONObject("dsl").getString("mode");
			JSONObject body = new JSONObject();
			if ("error".equals(mode)) {
				body.put("error", "index_not_found_exception");
			} else {
				JSONObject answer = new JSONObject();
				answer.put("answer", JSONArray.of(JSONObject.of("id", 1), JSONObject.of("id", 2)));
				answer.put("mqls", JSONArray.of());
				if ("window".equals(mode)) answer.put("resultWindow", JSONObject.of("contract", "onn_binding_window_v1", "answerComplete", true,
					"sourceExhausted", false, "matchingTotal", 262144, "totalExact", true, "offset", 0, "count", 2, "returnedRows", 2,
					"consistency", "version_checked", "dataVersion", "boot:1"));
				if (mode.startsWith("partial")) {
					answer.put("truncated", true);
					answer.put("rowLimit", 100000);
					answer.put("truncationHint", HINT);
				}
				body.put("data", JSONArray.of(answer));
			}
			String text = "partial-pretty".equals(mode) ? body.toJSONString(JSONWriter.Feature.PrettyFormat) : body.toJSONString();
			byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		};
		standIn.createContext("/dsl/execute", handler);
		standIn.createContext("/other/execute", handler);
		standIn.start();
		return standIn;
	}

	@AfterAll
	static void stop() {
		if (server != null) {
			server.stop(0);
			foreignServer.stop(0);
		}
	}

	@Test
	void windowSourceRangesSurviveRealPythonCalculationWithoutBudgetTruncation() throws Exception {
		PythonExecuteResult result = run(DslToPythonCodeUtil.generate(JSONObject.of("mode", "window"), port()));
		assertNull(result.getError(), result.getError());
		JSONObject marked = new JSONObject(); result.markCompleteness(marked);
		assertEquals(262144, marked.getJSONArray("sourceWindows").getJSONObject(0).getIntValue("matchingTotal"));
		assertFalse(marked.containsKey("truncated"));
		assertTrue(result.partialResultNotice().contains("window"));
	}

	@Test
	void legacyGeneratedProgramReportsPartialQueryWithoutTouchingOutput() throws Exception {
		PythonExecuteResult result = run(DslToPythonCodeUtil.generate(JSONObject.of("mode", "partial"), port()));

		assertNull(result.getError(), result.getError());
		assertEquals(2, JSONObject.parseObject(result.getResult()).getJSONArray("answer").size());
		JSONObject marked = new JSONObject();
		result.markCompleteness(marked);
		assertEquals(Boolean.TRUE, marked.getBoolean("truncated"));
		assertEquals(100000, marked.getInteger("rowLimit"));
		assertEquals(HINT, marked.getString("truncationHint"));
		assertTrue(result.partialResultNotice().contains(HINT));
	}

	@Test
	void reducingAPartialQueryToOneStatisticKeepsThePartialStatus() throws Exception {
		// The ABC programmer prompt's helper shape; the program prints only a count.
		String code = """
				import sys
				import json
				import requests
				userId = sys.argv[1]
				domainId = sys.argv[2]
				def executeDsl(dsl: dict) -> list:
				    resp = requests.post("http://127.0.0.1:%s/dsl/execute", json={"dsl": dsl, "userId": userId, "domainId": domainId}).json()
				    if resp.get("error"):
				        raise Exception(resp.get("error"))
				    return resp["data"][0].get("answer", [])
				rows = executeDsl({"mode": "partial"})
				rows += executeDsl({"mode": "complete"})
				print(json.dumps({"answer": [{"count": len(rows)}]}))
				""".formatted(port());
		PythonExecuteResult result = run(code);

		assertNull(result.getError(), result.getError());
		assertEquals(1, JSONObject.parseObject(result.getResult()).getJSONArray("answer").size());
		assertNotNull(result.getPartialQuery());
	}

	@Test
	void aFreshCompleteRunDoesNotInheritAnEarlierPartialRun() throws Exception {
		assertNotNull(run(DslToPythonCodeUtil.generate(JSONObject.of("mode", "partial"), port())).getPartialQuery());

		PythonExecuteResult complete = run(DslToPythonCodeUtil.generate(JSONObject.of("mode", "complete"), port()));

		assertNull(complete.getError(), complete.getError());
		assertNull(complete.getPartialQuery());
		JSONObject marked = JSONObject.of("answer", JSONArray.of());
		complete.markCompleteness(marked);
		assertFalse(marked.containsKey("truncated"));
		assertEquals("", complete.partialResultNotice());
	}

	@Test
	void aQueryErrorStaysAnError() throws Exception {
		PythonExecuteResult result = run(DslToPythonCodeUtil.generate(JSONObject.of("mode", "error"), port()));

		assertNotNull(result.getError());
		assertTrue(result.getError().contains("index_not_found_exception"), result.getError());
		assertNull(result.getPartialQuery());
	}

	@Test
	void aRunThatFailsAfterAPartialQueryReportsOnlyTheError() throws Exception {
		String code = """
				import json
				import requests
				import traceback
				try:
				    rows = requests.post("http://127.0.0.1:%s/dsl/execute", json={"dsl": {"mode": "partial"}}).json()["data"][0]["answer"]
				    raise ValueError("calculation failed after the query")
				except Exception:
				    traceback.print_exc()
				""".formatted(port());
		PythonExecuteResult result = run(code);

		assertNotNull(result.getError());
		assertTrue(result.getError().contains("calculation failed after the query"), result.getError());
		assertNotNull(result.getPartialQuery());
		assertEquals("", result.partialResultNotice());
	}

	@Test
	void prettyPrintedAndStreamedEngineAnswersAreRecorded() throws Exception {
		PythonExecuteResult pretty = run(post("http://localhost:" + port() + "/dsl/execute", "partial-pretty", false));
		assertNull(pretty.getError(), pretty.getError());
		assertEquals(HINT, pretty.getPartialQuery().getString("truncationHint"));

		PythonExecuteResult streamed = run(post("http://127.0.0.1:" + port() + "/dsl/execute", "partial", true));
		assertNull(streamed.getError(), streamed.getError());
		assertNotNull(streamed.getPartialQuery());
	}

	@Test
	void partialAnswersFromAnotherOriginOrPathAreNotRecorded() throws Exception {
		int foreignPort = foreignServer.getAddress().getPort();
		PythonExecuteResult otherPort = run(post("http://127.0.0.1:" + foreignPort + "/dsl/execute", "partial", false));
		assertNull(otherPort.getError(), otherPort.getError());
		assertNull(otherPort.getPartialQuery());

		PythonExecuteResult otherPath = run(post("http://127.0.0.1:" + port() + "/other/execute", "partial", false));
		assertNull(otherPath.getError(), otherPath.getError());
		assertNull(otherPath.getPartialQuery());
	}

	@Test
	void undecodableEngineAnswerKeepsTheDecoderError() throws Exception {
		String code = """
				import requests
				resp = requests.Response()
				resp.status_code = 200
				resp._content = b"not json"
				resp.request = requests.Request("POST", "http://127.0.0.1:%s/dsl/execute").prepare()
				try:
				    resp.json()
				except requests.exceptions.JSONDecodeError:
				    print('{"answer": [{"decoder": "unchanged"}]}')
				""".formatted(port());
		PythonExecuteResult result = run(code);

		assertNull(result.getError(), result.getError());
		assertTrue(result.getResult().contains("unchanged"), result.getResult());
		assertNull(result.getPartialQuery());
	}

	@Test
	void theProgramKeepsItsOwnArgvPathAndWorkingDirectory() throws Exception {
		String code = """
				import json, os, sys
				print(json.dumps({"argv": sys.argv, "file": __file__, "path0": sys.path[0], "cwd": os.getcwd(), "name": __name__}))
				""";
		Path program = workspace.resolve("argv_check.py");
		Files.writeString(program, code);
		PythonExecuteResult result = service.runPythonFile("argv-check", program.toFile(), 1, "en", "user-1", "domain-1");

		assertNull(result.getError(), result.getError());
		JSONObject seen = JSONObject.parseObject(result.getResult());
		String expected = program.toFile().getAbsolutePath();
		assertEquals(JSONArray.of(expected, "user-1", "domain-1"), seen.getJSONArray("argv"));
		assertEquals(expected, seen.getString("file"));
		Path directory = Path.of(expected).getParent().toRealPath();
		assertEquals(directory, Path.of(seen.getString("path0")).toRealPath());
		assertEquals(directory, Path.of(seen.getString("cwd")).toRealPath());
		assertEquals("__main__", seen.getString("name"));
	}

	/** A program that posts one DSL to {@code url}, decodes it with {@code .json()} and prints the rows. */
	private static String post(String url, String mode, boolean stream) {
		return """
				import json
				import requests
				resp = requests.post("%s", json={"dsl": {"mode": "%s"}, "userId": "admin", "domainId": "domain"}, stream=%s).json()
				print(json.dumps({"answer": resp["data"][0]["answer"]}))
				""".formatted(url, mode, stream ? "True" : "False");
	}

	private PythonExecuteResult run(String code) throws Exception {
		Path program = workspace.resolve("program-" + UUID.randomUUID() + ".py");
		Files.writeString(program, code);
		return service.runPythonFile(program.getFileName().toString(), program.toFile(), 1, "en", "admin", "domain");
	}

	private static String port() {
		return String.valueOf(server.getAddress().getPort());
	}

	private static boolean pythonHasRequests(String python) {
		try {
			return new ProcessBuilder(python, "-c", "import requests").start().waitFor() == 0;
		} catch (IOException | InterruptedException e) {
			return false;
		}
	}
}
