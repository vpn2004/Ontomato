package io.ontomato.dataengine.dataAdapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

/**
 * Proves the bounded join engine: a class larger than the in-memory cap is streamed (never
 * materialized) and oversized working sets produce marked partial results instead of an OOM.
 * Uses an explicit test endpoint or an isolated Testcontainers engine.
 *
 * <p>The cap is lowered through {@code ontomato.es.maxMaterialize} so a few hundred documents
 * exercise the streaming path.</p>
 */
class EsAdapterScaleTest {

	private static final String EXTERNAL_URL = System.getenv("ONTOMATO_TEST_ES_URL");
	private static final String EXTERNAL_USER = System.getenv("ONTOMATO_TEST_ES_USER");
	private static final String EXTERNAL_PASSWORD = System.getenv("ONTOMATO_TEST_ES_PASSWORD");

	private static final String CAP = "ontomato.es.maxMaterialize";

	/** The engine under test: {@code elasticsearch} (default) or {@code opensearch}. */
	private static final EsDialect DIALECT = "opensearch".equalsIgnoreCase(System.getenv("ONTOMATO_TEST_ES_DIALECT"))
			? EsDialect.OPENSEARCH
			: EsDialect.ELASTICSEARCH;

	private static String url;
	private static String user;
	private static String password;
	private static GenericContainer<?> container;

	private final String ns = "scale_" + System.nanoTime() + "_";

	@BeforeAll
	static void connect() {
		if (EXTERNAL_URL != null) {
			url = EXTERNAL_URL;
			user = EXTERNAL_USER;
			password = EXTERNAL_PASSWORD;
			return;
		}
		Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is unavailable");
		String image = System.getenv().getOrDefault("ONTOMATO_TEST_ES_IMAGE", DIALECT == EsDialect.OPENSEARCH
				? "opensearchproject/opensearch:2.19.0" : "docker.elastic.co/elasticsearch/elasticsearch:9.5.5");
		container = new GenericContainer<>(image).withEnv("discovery.type", "single-node")
				.withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m").withExposedPorts(9200)
				.waitingFor(Wait.forHttp("/").forPort(9200).withStartupTimeout(java.time.Duration.ofMinutes(3)));
		if (DIALECT == EsDialect.OPENSEARCH) {
			container.withEnv("DISABLE_SECURITY_PLUGIN", "true").withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true");
		} else {
			container.withEnv("xpack.security.enabled", "false");
		}
		container.start();
		url = "http://" + container.getHost() + ":" + container.getMappedPort(9200);
	}

	@AfterAll
	static void stopEngine() {
		if (container != null) container.stop();
	}

	@Test
	void streamingJoinBeyondTheBuildCapKeepsOnlyMatches() throws Exception {
		String previous = System.getProperty(CAP);
		System.setProperty(CAP, "100");
		try {
			seed(300, 5);
			List<JSONObject> rows = read(joinDsl());
			assertEquals(5, rows.size(), "only the 5 matching big rows survive the streamed join");
		} finally {
			restore(previous);
			cleanup();
		}
	}

	@Test
	void joinIntermediateReturnsPartialAndDoesNotMislabelTheExactBoundary() throws Exception {
		String previous = System.getProperty(CAP);
		System.setProperty(CAP, "100");
		try {
			seed(300, 100);
			JSONObject complete = answer(joinDsl());
			assertEquals(100, complete.getJSONArray("answer").size());
			assertNull(complete.getBoolean("truncated"));
			cleanup();
			seed(300, 101);
			JSONObject partial = answer(joinDsl());
			assertEquals(100, partial.getJSONArray("answer").size());
			assertEquals(true, partial.getBoolean("truncated"));
			assertEquals(100, partial.getIntValue("rowLimit"));
			assertTrue(partial.getString("truncationHint").contains("joined intermediate"));
		} finally {
			restore(previous);
			cleanup();
		}
	}

	@Test
	void twoStepDslAggregatesOverStepZero() throws Exception {
		String previous = System.getProperty(CAP);
		System.setProperty(CAP, "100");
		try {
			seed(300, 5);
			List<JSONObject> rows = read(twoStepDsl());
			assertEquals(2, rows.size(), "one group per small_id in step0's rows");
			long total = rows.stream().mapToLong(row -> row.getLongValue("cnt")).sum();
			assertEquals(5, total, "the counts sum to the 5 matched rows");
		} finally {
			restore(previous);
			cleanup();
		}
	}

	@Test
	void partialIntermediateSurvivesAggregationAndACompleteNextQuery() throws Exception {
		String previous = System.getProperty(CAP);
		System.setProperty(CAP, "100");
		try {
			seed(300, 300);
			JSONArray dsls = JSONArray.parseArray(twoStepDsl());
			dsls.getJSONObject(0).getJSONObject("answer").getJSONArray("steps").getJSONObject(0)
					.getJSONObject("output").put("limit", JSONObject.of("count", 200, "offset", 0));
			EsAdapter adapter = adapter(readRule());
			JSONObject result = JSONArray.parseArray(adapter.executeDsl(dsls.toJSONString(), "scale", null, new java.util.ArrayList<>())).getJSONObject(0);
			assertEquals(true, result.getBoolean("truncated"));
			assertEquals(100, result.getIntValue("rowLimit"));
			assertEquals(100L, result.getJSONArray("answer").stream().mapToLong(row -> ((JSONObject) row).getLongValue("cnt")).sum());
			dsls.getJSONObject(0).getJSONObject("answer").getJSONArray("steps").getJSONObject(1)
					.getJSONObject("output").getJSONObject("group_by").put("having", JSONArray.of(JSONObject.of(
							"variable", "tmp", "field", "small_id", "function", "count", "operator", ">", "value", 1000)));
			JSONObject empty = answer(dsls.toJSONString());
			assertTrue(empty.getJSONArray("answer").isEmpty());
			assertEquals(true, empty.getBoolean("truncated"));
			JSONObject complete = JSONArray.parseArray(adapter.executeDsl(aggregateOverJoinDsl(), "next", null, new java.util.ArrayList<>())).getJSONObject(0);
			assertNull(complete.getBoolean("truncated"));
			assertEquals(300L, complete.getJSONArray("answer").stream().mapToLong(row -> ((JSONObject) row).getLongValue("cnt")).sum());
		} finally { restore(previous); cleanup(); }
	}

	@Test
	void nativeGroupBudgetAndJoinDistinctBudgetRemainExplicit() throws Exception {
		String previous = System.getProperty(CAP);
		System.setProperty(CAP, "10");
		try {
			seed(30, 30);
			JSONObject step = joinStep();
			JSONObject pattern = step.getJSONObject("graph").getJSONArray("patterns").getJSONObject(0);
			pattern.getJSONArray("objects").remove(1);
			pattern.remove("relationship");
			JSONObject output = step.getJSONObject("output");
			output.getJSONArray("fields").add(JSONObject.of("variable", "b", "field", "id", "as", "count", "function", "count"));
			output.put("group_by", JSONObject.of("fields", JSONArray.of(JSONObject.of("variable", "b", "field", "id"))));
			JSONObject groups = answer(JSONArray.of(oneStepAnswer(step)).toJSONString());
			assertEquals(10, groups.getJSONArray("answer").size());
			assertEquals(true, groups.getBoolean("truncated"));
			JSONObject joined = joinStep();
			JSONObject count = joined.getJSONObject("output").getJSONArray("fields").getJSONObject(0);
			count.put("function", "count");
			count.put("distinct", true);
			JSONObject distinct = answer(JSONArray.of(oneStepAnswer(joined)).toJSONString());
			assertEquals(10L, distinct.getJSONArray("answer").getJSONObject(0).getLongValue("big_id"));
			assertEquals(true, distinct.getBoolean("truncated"));
		} finally { restore(previous); cleanup(); }
	}

	@Test
	void nativeLimitSortCountAndExactDistinctDoNotLoadTheWholeIndex() throws Exception {
		String previous = System.getProperty(CAP);
		System.setProperty(CAP, "10");
		try {
			seed(300, 300);
			JSONObject step = joinStep();
			JSONObject pattern = step.getJSONObject("graph").getJSONArray("patterns").getJSONObject(0);
			pattern.getJSONArray("objects").remove(1);
			pattern.remove("relationship");
			JSONObject output = step.getJSONObject("output");
			output.put("limit", JSONObject.of("offset", 2, "count", 3));
			output.put("sort", JSONObject.of("fields", JSONArray.of(JSONObject.of("field", "big_id", "order", "desc"))));
			JSONObject sample = answer(JSONArray.of(oneStepAnswer(step)).toJSONString());
			assertEquals(List.of("b97", "b96", "b95"), sample.getJSONArray("answer").stream().map(row -> ((JSONObject) row).getString("big_id")).toList());
			assertNull(sample.getBoolean("truncated"));
			output.remove("sort");
			output.remove("limit");
			JSONObject field = output.getJSONArray("fields").getJSONObject(0);
			field.put("function", "count");
			JSONObject count = answer(JSONArray.of(oneStepAnswer(step)).toJSONString());
			assertEquals(300L, count.getJSONArray("answer").getJSONObject(0).getLongValue("big_id"));
			assertNull(count.getBoolean("truncated"));
			field.put("field", "small_id");
			field.put("distinct", true);
			JSONObject distinct = answer(JSONArray.of(oneStepAnswer(step)).toJSONString());
			assertEquals(2L, distinct.getJSONArray("answer").getJSONObject(0).getLongValue("big_id"));
			assertNull(distinct.getBoolean("truncated"));
		} finally { restore(previous); cleanup(); }
	}

	@Test
	void streamingAggregationAvoidsTheIntermediateCap() throws Exception {
		String previous = System.getProperty(CAP);
		System.setProperty(CAP, "100");
		try {
			// The same 300 joined rows that truncate a plain join are complete here: aggregation
			// streams into per-group accumulators instead of materializing the joined set.
			seed(300, 300);
			List<JSONObject> rows = read(aggregateOverJoinDsl());
			assertEquals(2, rows.size(), "one group per small_id");
			long total = rows.stream().mapToLong(row -> row.getLongValue("cnt")).sum();
			assertEquals(300, total, "every joined row was counted");
		} finally {
			restore(previous);
			cleanup();
		}
	}

	/**
	 * The default row cap is a terminal-step bound: step 0's working set is aggregated in full, never
	 * silently truncated. With a 2-row cap and 300 step-0 rows, a capped inner step would count 2; the
	 * sum stays 300 only if the cap did not touch the inner step.
	 */
	@Test
	void twoStepInnerStepIsNotCappedByTheDefaultRowLimit() throws Exception {
		String previous = System.getProperty("ontomato.dsl.defaultRowLimit");
		System.setProperty("ontomato.dsl.defaultRowLimit", "2");
		try {
			seed(300, 300);
			List<JSONObject> rows = read(twoStepDsl());
			assertEquals(2, rows.size(), "one group per small_id");
			long total = rows.stream().mapToLong(row -> row.getLongValue("cnt")).sum();
			assertEquals(300, total, "the inner step was not truncated by the default row cap");
		} finally {
			restoreProperty("ontomato.dsl.defaultRowLimit", previous);
			cleanup();
		}
	}

	// ----------------------------------------------------------------------------------

	private void seed(int bigRows, int matchingRows) throws Exception {
		EsAdapter seeder = adapter(seedRule());
		seeder.createClass(ns, classDef("big"));
		seeder.createClass(ns, classDef("small"));
		seeder.insertObjects(ns, classDef("small"), JSONArray.of(small("s1", "one"), small("s2", "two")));
		JSONArray bigs = new JSONArray();
		for (int i = 0; i < bigRows; i++) {
			bigs.add(big("b" + i, i < matchingRows ? (i % 2 == 0 ? "s1" : "s2") : "nope"));
		}
		seeder.insertObjects(ns, classDef("big"), bigs);
	}

	private List<JSONObject> read(String dsl) throws Exception {
		return answer(dsl).getJSONArray("answer").toJavaList(JSONObject.class);
	}

	private JSONObject answer(String dsl) throws Exception {
		EsAdapter adapter = adapter(readRule());
		JSONArray answers = JSONArray.parseArray(adapter.executeDsl(dsl, "scale", null, new java.util.ArrayList<>()));
		return answers.getJSONObject(0);
	}

	private void cleanup() {
		try {
			EsAdapter adapter = adapter(seedRule());
			adapter.dropNamespace(ns);
		} catch (Exception ignored) {
			// best effort
		}
	}

	private void restore(String previous) {
		restoreProperty(CAP, previous);
	}

	private void restoreProperty(String key, String previous) {
		if (previous == null) {
			System.clearProperty(key);
		} else {
			System.setProperty(key, previous);
		}
	}

	private EsAdapter adapter(JSONObject rule) {
		DataAdapterConnection connection = new DataAdapterConnection();
		connection.setUrl(url);
		connection.setUser(user);
		connection.setPassword(password);
		return new EsAdapter(connection, rule, DIALECT);
	}

	private JSONObject seedRule() {
		return rule(false);
	}

	private JSONObject readRule() {
		return rule(true);
	}

	private JSONObject rule(boolean prefixed) {
		JSONObject rule = new JSONObject();
		rule.put("classDef", JSONArray.of(classDef(prefixed ? ns + "big" : "big"),
				classDef(prefixed ? ns + "small" : "small")));
		JSONObject belongs = new JSONObject();
		belongs.put("fromclass", "big");
		belongs.put("toclass", "small");
		belongs.put("fromField", "small_id");
		belongs.put("toField", "id");
		JSONObject relationships = new JSONObject();
		relationships.put("belongs", belongs);
		rule.put("relationship_rule", relationships);
		return rule;
	}

	private JSONObject classDef(String className) {
		JSONObject classDef = new JSONObject();
		classDef.put("className", className);
		if (className.endsWith("small")) {
			classDef.put("attrs", JSONArray.of(attr("id", true), attr("label", false)));
		} else {
			classDef.put("attrs", JSONArray.of(attr("id", true), attr("name", false), attr("small_id", false)));
		}
		return classDef;
	}

	private JSONObject attr(String name, boolean primaryKey) {
		JSONObject attr = new JSONObject();
		attr.put("name", name);
		attr.put("type", "varchar");
		attr.put("primaryKey", primaryKey);
		attr.put("enable", true);
		return attr;
	}

	private JSONObject small(String id, String label) {
		JSONObject object = new JSONObject();
		object.put("id", id);
		object.put("label", label);
		return object;
	}

	private JSONObject big(String id, String smallId) {
		JSONObject object = new JSONObject();
		object.put("id", id);
		object.put("name", id);
		object.put("small_id", smallId);
		return object;
	}

	private String joinDsl() {
		return JSONArray.of(oneStepAnswer(joinStep())).toJSONString();
	}

	/** Step 0 = the graph pattern and a plain projection; step 1 = group by + count over step 0. */
	private String twoStepDsl() {
		JSONObject answer = new JSONObject();
		answer.put("steps", JSONArray.of(joinStep("small_id", "small_id"), aggregateStep()));
		JSONObject dsl = new JSONObject();
		dsl.put("answer", answer);
		return JSONArray.of(dsl).toJSONString();
	}

	private JSONObject oneStepAnswer(JSONObject step) {
		JSONObject answer = new JSONObject();
		answer.put("steps", JSONArray.of(step));
		JSONObject dsl = new JSONObject();
		dsl.put("answer", answer);
		return dsl;
	}

	private JSONObject aggregateStep() {
		JSONObject tmp = new JSONObject();
		tmp.put("class", ns + "tmp");
		tmp.put("variable", "tmp");
		tmp.put("idx", 0);
		JSONObject pattern = new JSONObject();
		pattern.put("objects", JSONArray.of(tmp));
		JSONObject graph = new JSONObject();
		graph.put("patterns", JSONArray.of(pattern));
		JSONObject sid = new JSONObject();
		sid.put("variable", "tmp");
		sid.put("field", "small_id");
		sid.put("as", "sid");
		JSONObject cnt = new JSONObject();
		cnt.put("variable", "tmp");
		cnt.put("field", "small_id");
		cnt.put("as", "cnt");
		cnt.put("function", "count");
		JSONObject groupField = new JSONObject();
		groupField.put("variable", "tmp");
		groupField.put("field", "small_id");
		JSONObject groupBy = new JSONObject();
		groupBy.put("fields", JSONArray.of(groupField));
		JSONObject output = new JSONObject();
		output.put("fields", JSONArray.of(sid, cnt));
		output.put("group_by", groupBy);
		JSONObject step = new JSONObject();
		step.put("graph", graph);
		step.put("output", output);
		return step;
	}

	/** One step: join big ⋈ small, then group by small.id with a count. */
	private String aggregateOverJoinDsl() {
		JSONObject step = joinStep("id", "big_id");
		JSONObject sid = new JSONObject();
		sid.put("variable", "s");
		sid.put("field", "id");
		sid.put("as", "sid");
		JSONObject cnt = new JSONObject();
		cnt.put("variable", "s");
		cnt.put("field", "id");
		cnt.put("as", "cnt");
		cnt.put("function", "count");
		JSONObject groupField = new JSONObject();
		groupField.put("variable", "s");
		groupField.put("field", "id");
		JSONObject groupBy = new JSONObject();
		groupBy.put("fields", JSONArray.of(groupField));
		JSONObject output = new JSONObject();
		output.put("fields", JSONArray.of(sid, cnt));
		output.put("group_by", groupBy);
		step.put("output", output);
		return JSONArray.of(oneStepAnswer(step)).toJSONString();
	}

	private JSONObject joinStep() {
		return joinStep("id", "big_id");
	}

	private JSONObject joinStep(String fieldName, String as) {
		JSONObject bigObject = new JSONObject();
		bigObject.put("class", ns + "big");
		bigObject.put("variable", "b");
		bigObject.put("idx", 0);
		JSONObject smallObject = new JSONObject();
		smallObject.put("class", ns + "small");
		smallObject.put("variable", "s");
		smallObject.put("idx", 1);
		JSONObject relation = new JSONObject();
		relation.put("from", 0);
		relation.put("to", 1);
		relation.put("type", JSONArray.of("belongs"));
		JSONObject pattern = new JSONObject();
		pattern.put("objects", JSONArray.of(bigObject, smallObject));
		pattern.put("relationship", JSONArray.of(relation));
		JSONObject graph = new JSONObject();
		graph.put("patterns", JSONArray.of(pattern));
		JSONObject field = new JSONObject();
		field.put("variable", "b");
		field.put("field", fieldName);
		field.put("as", as);
		JSONObject output = new JSONObject();
		output.put("fields", JSONArray.of(field));
		JSONObject step = new JSONObject();
		step.put("graph", graph);
		step.put("output", output);
		return step;
	}
}
