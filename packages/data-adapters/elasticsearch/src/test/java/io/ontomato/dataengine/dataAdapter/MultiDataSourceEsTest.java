package io.ontomato.dataengine.dataAdapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import io.ontomato.dataengine.config.BusinessConfig;
import io.ontomato.dataengine.config.DataRagConfig;
import io.ontomato.dataengine.config.multisource.ClassRoute;
import io.ontomato.dataengine.config.multisource.DataSourceEntry;
import io.ontomato.dataengine.config.multisource.MultiDataSourceDefinition;

/**
 * The second layer of the multi-source tests over a real search engine.
 *
 * <p>Elasticsearch runs its query inside the adapter (there is no SQL to render and no cross-query
 * temporary relation), so it is the one path where the two-step DSL is materialized by the adapter
 * itself. This pins that the wrapper leaves a real search answer unchanged and that a route reaches
 * the intended index.</p>
 *
 * <p>Provisioning mirrors {@code EsAdapterTest}: a Testcontainers instance when Docker is available, an
 * external URL through {@code ONTOMATO_TEST_ES_URL} otherwise, and a skip (not a failure) otherwise.
 * Indices are seeded over plain HTTP so this stays independent of the adapter's own write path.</p>
 */
class MultiDataSourceEsTest {

	private static final String EXTERNAL_URL = System.getenv("ONTOMATO_TEST_ES_URL");
	private static final String IMAGE = System.getenv().getOrDefault("ONTOMATO_TEST_ES_IMAGE",
			"docker.elastic.co/elasticsearch/elasticsearch:9.5.5");

	private static String url;
	private static GenericContainer<?> container;

	private static final String PERSON = "md_person_" + Long.toHexString(System.nanoTime());
	private static final String INVOICE = "md_invoice_" + Long.toHexString(System.nanoTime());

	@BeforeAll
	static void startEngine() throws Exception {
		if (EXTERNAL_URL != null) {
			url = EXTERNAL_URL;
		} else {
			Assumptions.assumeTrue(isDockerAvailable(),
					"Docker is not running and ONTOMATO_TEST_ES_URL is not set; skipping multi-source Elasticsearch tests");
			container = new GenericContainer<>(IMAGE)
					.withEnv("discovery.type", "single-node")
					.withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
					.withEnv("xpack.security.enabled", "false")
					.withExposedPorts(9200)
					.waitingFor(Wait.forHttp("/").forPort(9200).withStartupTimeout(java.time.Duration.ofMinutes(3)));
			container.start();
			url = "http://" + container.getHost() + ":" + container.getMappedPort(9200);
		}
		seed(PERSON, "crm-only");
		seed(INVOICE, "erp-only");
	}

	private static boolean isDockerAvailable() {
		try {
			return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
		} catch (Throwable t) {
			return false;
		}
	}

	/** One document, refreshed so the next search sees it. */
	private static void seed(String index, String name) throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/" + index + "/_doc/1?refresh=true"))
				.header("Content-Type", "application/json")
				.PUT(HttpRequest.BodyPublishers.ofString("{\"id\":\"1\",\"name\":\"" + name + "\"}"))
				.build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertTrue(response.statusCode() < 300, response.statusCode() + " " + response.body());
	}

	// ----------------------------------------------------------------------------------
	// Fixtures
	// ----------------------------------------------------------------------------------

	private static JSONObject classDef(String className) {
		JSONArray attrs = new JSONArray();
		attrs.add(attr("id", "varchar", true));
		attrs.add(attr("name", "varchar", false));
		return new JSONObject().fluentPut("className", className).fluentPut("attrs", attrs);
	}

	private static JSONObject attr(String name, String type, boolean primaryKey) {
		JSONObject attr = new JSONObject();
		attr.put("name", name);
		attr.put("type", type);
		attr.put("primaryKey", primaryKey);
		attr.put("enable", true);
		return attr;
	}

	private static JSONObject jsonRule(String... classNames) {
		JSONArray classDefs = new JSONArray();
		for (String className : classNames) {
			classDefs.add(classDef(className));
		}
		return new JSONObject().fluentPut("classDef", classDefs).fluentPut("relationship_rule", new JSONObject());
	}

	private static String dsl(String className) {
		JSONObject object = new JSONObject().fluentPut("idx", 0).fluentPut("variable", "v").fluentPut("class", className);
		JSONObject pattern = new JSONObject().fluentPut("objects", JSONArray.of(object));
		JSONObject graph = new JSONObject().fluentPut("patterns", JSONArray.of(pattern));
		JSONObject field = new JSONObject().fluentPut("variable", "v").fluentPut("field", "name").fluentPut("as", "name");
		JSONObject output = new JSONObject().fluentPut("fields", JSONArray.of(field));
		JSONObject step = new JSONObject().fluentPut("graph", graph).fluentPut("output", output);
		JSONObject answer = new JSONObject().fluentPut("steps", JSONArray.of(step));
		return JSONArray.of(new JSONObject().fluentPut("answer", answer)).toJSONString();
	}

	private static DataAdapterConnection connection() {
		DataAdapterConnection connection = new DataAdapterConnection();
		connection.setUrl(url);
		return connection;
	}

	private static JSONArray rowsOf(String result) {
		return JSON.parseArray(result).getJSONObject(0).getJSONArray("answer");
	}

	private static DataSourceEntry source(String id) {
		DataSourceEntry entry = new DataSourceEntry();
		entry.setId(id);
		entry.setAdapterType("elasticsearch");
		entry.setConnection(connection());
		entry.setLabel(id);
		return entry;
	}

	private static ClassRoute route(String className, String sourceId) {
		ClassRoute route = new ClassRoute();
		route.setForOntoMap(className);
		route.setSourceId(sourceId);
		return route;
	}

	private MultiDataSourceAdapter multi(JSONObject jsonRule, String... sourceIds) {
		BusinessConfig businessConfig = new BusinessConfig();
		businessConfig.setDataAdapter("multi");
		MultiDataSourceDefinition definition = new MultiDataSourceDefinition();
		for (String sourceId : sourceIds) {
			definition.getDataSources().add(source(sourceId));
		}
		definition.getClasses().add(route(PERSON, "crm"));
		definition.getClasses().add(route(INVOICE, "erp"));
		businessConfig.setMultiDataSource(definition);
		DataAdapterRegistry registry = new DataAdapterRegistry(List.of(new ElasticsearchAdapterProvider()), new DataRagConfig());
		return new MultiDataSourceAdapter(registry, businessConfig, new DataRagConfig(), jsonRule);
	}

	// ----------------------------------------------------------------------------------
	// Tests
	// ----------------------------------------------------------------------------------

	@Test
	void wrappingARealSearchAdapterChangesNothing() throws Exception {
		JSONObject jsonRule = jsonRule(PERSON);
		DataAdapter direct = new EsAdapter(connection(), jsonRule, EsDialect.ELASTICSEARCH);
		MultiDataSourceAdapter wrapped = new MultiDataSourceAdapter(
				new DataAdapterRegistry(List.of(new ElasticsearchAdapterProvider()), new DataRagConfig()),
				businessConfigOneSource(), new DataRagConfig(), jsonRule);

		String directResult = direct.executeDsl(dsl(PERSON), "s", null, new ArrayList<>());
		String wrappedResult = wrapped.executeDsl(dsl(PERSON), "s", null, new ArrayList<>());

		assertEquals(1, rowsOf(directResult).size(), directResult);
		assertEquals("crm-only", rowsOf(directResult).getJSONObject(0).getString("name"));
		assertEquals(rowsOf(directResult), rowsOf(wrappedResult));
	}

	private BusinessConfig businessConfigOneSource() {
		BusinessConfig businessConfig = new BusinessConfig();
		businessConfig.setDataAdapter("multi");
		MultiDataSourceDefinition definition = new MultiDataSourceDefinition();
		definition.getDataSources().add(source("crm"));
		definition.getClasses().add(route(PERSON, "crm"));
		businessConfig.setMultiDataSource(definition);
		return businessConfig;
	}

	@Test
	void eachClassRoutesToItsOwnIndex() throws Exception {
		MultiDataSourceAdapter adapter = multi(jsonRule(PERSON, INVOICE), "crm", "erp");

		assertEquals("crm-only", rowsOf(adapter.executeDsl(dsl(PERSON), "s", null, new ArrayList<>()))
				.getJSONObject(0).getString("name"));
		assertEquals("erp-only", rowsOf(adapter.executeDsl(dsl(INVOICE), "s", null, new ArrayList<>()))
				.getJSONObject(0).getString("name"));
	}
}
