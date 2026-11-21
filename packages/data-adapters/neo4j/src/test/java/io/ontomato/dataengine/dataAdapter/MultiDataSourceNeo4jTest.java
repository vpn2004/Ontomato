package io.ontomato.dataengine.dataAdapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
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
import io.ontomato.dataengine.config.multisource.RelationshipRoute;

/**
 * The second layer of the multi-source tests over a real graph.
 *
 * <p>Neo4j traverses the edge name the DSL carries, so this is where a relationship whose ontology name
 * differs from its physical edge name has to be rewritten before the element is handed over. The test
 * pins that rewrite against a real instance: the ontology name does not exist as an edge type, and only a
 * correct rewrite makes the traversal find it. Writes go through the same translation, so an edge written
 * through the multi adapter under the ontology name is stored under the physical name and read back.</p>
 *
 * <p>Provisioning mirrors {@code Neo4jAdapterTest}: Testcontainers when Docker is available, an external
 * URL through {@code ONTOMATO_TEST_NEO4J_URL} otherwise, and a skip (not a failure) when neither is.</p>
 */
class MultiDataSourceNeo4jTest {

	private static final String EXTERNAL_URL = System.getenv("ONTOMATO_TEST_NEO4J_URL");
	private static final String EXTERNAL_USER = System.getenv("ONTOMATO_TEST_NEO4J_USER");
	private static final String EXTERNAL_PASSWORD = System.getenv("ONTOMATO_TEST_NEO4J_PASSWORD");

	/** The ontology calls it this. */
	private static final String ONTOLOGY_RELATION = "person_works_at";
	/** The store physically calls it that. */
	private static final String PHYSICAL_EDGE = "WORKS_AT";

	private static String url;
	private static String user;
	private static String password;
	private static GenericContainer<?> container;

	@BeforeAll
	static void startDatabase() {
		if (EXTERNAL_URL != null) {
			url = EXTERNAL_URL;
			user = EXTERNAL_USER == null ? "neo4j" : EXTERNAL_USER;
			password = EXTERNAL_PASSWORD == null ? "neo4j" : EXTERNAL_PASSWORD;
		} else {
			Assumptions.assumeTrue(isDockerAvailable(),
					"Docker is not running and ONTOMATO_TEST_NEO4J_URL is not set; skipping multi-source Neo4j tests");
			container = new GenericContainer<>("neo4j:5.26.0")
					.withEnv("NEO4J_AUTH", "neo4j/neo4j-test")
					.withExposedPorts(7687, 7474)
					.waitingFor(Wait.forLogMessage(".*Started.*", 1));
			container.start();
			url = "bolt://" + container.getHost() + ":" + container.getMappedPort(7687);
			user = "neo4j";
			password = "neo4j-test";
		}
		seed();
	}

	private static boolean isDockerAvailable() {
		try {
			return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
		} catch (Throwable t) {
			return false;
		}
	}

	/** One person working at one org, joined by the physical edge name only. */
	private static void seed() {
		String person = "md_person_" + Long.toHexString(System.nanoTime());
		String org = "md_org_" + Long.toHexString(System.nanoTime());
		try (Driver driver = GraphDatabase.driver(url, AuthTokens.basic(user, password));
				Session session = driver.session()) {
			session.run("CREATE (a:" + person + " {id:'1', name:'alice'})-[:WORKS_AT]->(b:" + org
					+ " {id:'2', name:'acme'})");
		}
		PERSON = person;
		ORG = org;
	}

	private static String PERSON;
	private static String ORG;

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

	private static JSONObject jsonRule() {
		JSONObject relationshipDef = new JSONObject()
				.fluentPut("fromclass", PERSON)
				.fluentPut("toclass", ORG)
				.fluentPut("fromField", "")
				.fluentPut("toField", "");
		return new JSONObject()
				.fluentPut("classDef", JSONArray.of(classDef(PERSON), classDef(ORG)))
				.fluentPut("relationship_rule", new JSONObject().fluentPut(ONTOLOGY_RELATION, relationshipDef));
	}

	/** One person joined to one org by the given relationship name, selecting the person's name. */
	private static String dsl(String relationshipName) {
		JSONObject first = new JSONObject().fluentPut("idx", 0).fluentPut("variable", "a").fluentPut("class", PERSON);
		JSONObject second = new JSONObject().fluentPut("idx", 1).fluentPut("variable", "b").fluentPut("class", ORG);
		JSONObject relationship = new JSONObject().fluentPut("from", 0).fluentPut("to", 1)
				.fluentPut("type", JSONArray.of(relationshipName));
		JSONObject pattern = new JSONObject().fluentPut("objects", JSONArray.of(first, second))
				.fluentPut("relationship", JSONArray.of(relationship));
		JSONObject graph = new JSONObject().fluentPut("patterns", JSONArray.of(pattern));
		JSONObject field = new JSONObject().fluentPut("variable", "a").fluentPut("field", "name").fluentPut("as", "name");
		JSONObject step = new JSONObject().fluentPut("graph", graph)
				.fluentPut("output", new JSONObject().fluentPut("fields", JSONArray.of(field)));
		return JSONArray.of(new JSONObject().fluentPut("answer",
				new JSONObject().fluentPut("steps", JSONArray.of(step)))).toJSONString();
	}

	private static DataAdapterConnection connection() {
		DataAdapterConnection connection = new DataAdapterConnection();
		connection.setUrl(url);
		connection.setUser(user);
		connection.setPassword(password);
		return connection;
	}

	private static JSONArray rowsOf(String result) {
		return JSON.parseArray(result).getJSONObject(0).getJSONArray("answer");
	}

	private static DataSourceEntry source(String id) {
		DataSourceEntry entry = new DataSourceEntry();
		entry.setId(id);
		entry.setAdapterType("neo4j");
		entry.setConnection(connection());
		entry.setLabel(id);
		return entry;
	}

	/** A multi adapter over one graph source, with the ontology name mapped to the physical edge name. */
	private MultiDataSourceAdapter multiWithPhysicalEdge() {
		BusinessConfig businessConfig = new BusinessConfig();
		businessConfig.setDataAdapter("multi");
		MultiDataSourceDefinition definition = new MultiDataSourceDefinition();
		definition.getDataSources().add(source("gph"));
		ClassRoute person = new ClassRoute();
		person.setForOntoMap(PERSON);
		person.setSourceId("gph");
		ClassRoute org = new ClassRoute();
		org.setForOntoMap(ORG);
		org.setSourceId("gph");
		definition.getClasses().add(person);
		definition.getClasses().add(org);
		RelationshipRoute relationship = new RelationshipRoute();
		relationship.setForOntoMap(ONTOLOGY_RELATION);
		relationship.setRealEdge(PHYSICAL_EDGE);
		definition.getRelationships().add(relationship);
		businessConfig.setMultiDataSource(definition);
		DataAdapterRegistry registry = new DataAdapterRegistry(List.of(new Neo4jAdapterProvider()), new DataRagConfig());
		return new MultiDataSourceAdapter(registry, businessConfig, new DataRagConfig(), jsonRule());
	}

	// ----------------------------------------------------------------------------------
	// Tests
	// ----------------------------------------------------------------------------------

	/** Control: with the physical name the traversal works, so the fixture itself is sound. */
	@Test
	void thePhysicalEdgeIsTraversableDirectly() throws Exception {
		DataAdapter direct = new Neo4jAdapter(connection(), jsonRule());

		JSONArray rows = rowsOf(direct.executeDsl(dsl(PHYSICAL_EDGE), "s", null, new ArrayList<>()));

		assertEquals(1, rows.size());
		assertEquals("alice", rows.getJSONObject(0).getString("name"));
	}

	/** The ontology name does not exist as an edge type, so a direct traversal finds nothing. */
	@Test
	void theOntologyNameIsNotAnEdgeTypeItself() throws Exception {
		DataAdapter direct = new Neo4jAdapter(connection(), jsonRule());

		assertTrue(rowsOf(direct.executeDsl(dsl(ONTOLOGY_RELATION), "s", null, new ArrayList<>())).isEmpty());
	}

	/** Through the multi adapter the ontology name is rewritten, so the same query now finds the person. */
	@Test
	void theMultiAdapterRewritesTheOntologyNameToThePhysicalEdge() throws Exception {
		List<JSONObject> rows = new ArrayList<>();
		rowsOf(multiWithPhysicalEdge().executeDsl(dsl(ONTOLOGY_RELATION), "s", null, new ArrayList<>()))
				.forEach(row -> rows.add((JSONObject) row));

		assertEquals(1, rows.size());
		assertEquals("alice", rows.get(0).getString("name"));
	}

	/** An edge written through the multi adapter lands under the physical name, where the read path looks. */
	@Test
	void anEdgeWrittenThroughTheMultiAdapterIsReadBackThroughIt() throws Exception {
		try (Driver driver = GraphDatabase.driver(url, AuthTokens.basic(user, password));
				Session session = driver.session()) {
			session.run("CREATE (:" + PERSON + " {id:'3', name:'bob'})");
		}
		MultiDataSourceAdapter multi = multiWithPhysicalEdge();
		try {
			multi.createEdge(null, ONTOLOGY_RELATION, PERSON, "3", ORG, "2");

			List<String> names = new ArrayList<>();
			rowsOf(multi.executeDsl(dsl(ONTOLOGY_RELATION), "s", null, new ArrayList<>()))
					.forEach(row -> names.add(((JSONObject) row).getString("name")));
			assertTrue(names.contains("bob"), names.toString());
			assertTrue(rowsOf(new Neo4jAdapter(connection(), jsonRule())
					.executeDsl(dsl(ONTOLOGY_RELATION), "s", null, new ArrayList<>())).isEmpty(),
					"no edge may be stored under the ontology name");
		} finally {
			multi.deleteEdge(null, ONTOLOGY_RELATION, PERSON, "3", ORG, "2");
		}
		List<String> afterDelete = new ArrayList<>();
		rowsOf(multi.executeDsl(dsl(ONTOLOGY_RELATION), "s", null, new ArrayList<>()))
				.forEach(row -> afterDelete.add(((JSONObject) row).getString("name")));
		assertEquals(List.of("alice"), afterDelete);
	}
}
