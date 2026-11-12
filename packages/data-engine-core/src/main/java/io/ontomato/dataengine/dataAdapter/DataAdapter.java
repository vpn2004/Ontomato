package io.ontomato.dataengine.dataAdapter;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.ontomato.dataengine.config.DataRagConfig;
import io.ontomato.dataengine.dao.VectorResourceDao;

public interface DataAdapter {

    /** Source-specific planning constraints appended to the shared DSL grammar. */
    default String queryPlanningRules() {
        return "";
    }

    /** Optional complete example compatible with the current source, replacing generic examples. */
    default String queryPlanningExample(List<String> classNames) {
        return "";
    }

    /** Maximum consecutive failed trial DSL executions in one Agent call; zero leaves legacy sources unchanged. */
    default int consecutiveDslFailureLimit() {
        return 0;
    }

	/** Product capabilities independent of the source query language. */
	default DataAdapterCapabilities capabilities() {
        return DataAdapterCapabilities.legacy(useM3());
	}

	/** Kept as a compatibility marker for callers that distinguish the historical M3 path. */
	default boolean useM3() {
		return false;
	}

	/** Read a backend schema projected into Ontomato's ontology document. */
	default Map<String, Object> readOntology() throws Exception {
		throw new UnsupportedOperationException("This adapter does not support ontology schema reads");
	}

	/** One-hop graph exploration, when the source exposes it. */
	default JSONObject queryNeighbors(String className, String objectId) throws Exception {
		throw new UnsupportedOperationException("This adapter does not support graph neighbors");
	}

	/** Resolve the identity field used by object detail/evidence paths. */
	default String objectPrimaryKey(Map<String, Object> classDef) {
		if (useM3()) return "id";
		return io.ontomato.dataengine.util.DslUtil.getPrimaryKey(classDef);
	}

	/** Encode a product node reference without assuming the source ID is numeric. */
	default String encodeNodeReference(String className, String storedId) {
		if (useM3()) return storedId;
		return className + ":" + storedId;
	}

	/** Decode a node reference while preserving colons inside the stored ID. */
	default String decodeNodeReference(String className, String nodeReference) {
		if (useM3()) return nodeReference;
		String prefix = className + ":";
		if (nodeReference == null || !nodeReference.startsWith(prefix)) {
			throw new IllegalArgumentException("Node reference does not belong to class " + className);
		}
		return nodeReference.substring(prefix.length());
	}

    /** Schema lifecycle hooks retained for the historical M3 management path. */
    default void createSchemaClass(Map<String, Object> classDef, DataRagConfig config) {
        capabilities().requireSchemaWrite();
        if (useM3()) LegacyM3SchemaOperations.createClass(classDef, config);
    }

    default void deleteSchemaClass(String className, DataRagConfig config) {
        capabilities().requireSchemaWrite();
        if (useM3()) LegacyM3SchemaOperations.deleteClass(className, config);
    }

    default void createSchemaRelationship(String name, Map<String, String> relationDef, DataRagConfig config) {
        capabilities().requireSchemaWrite();
        if (useM3()) LegacyM3SchemaOperations.createRelationship(name, relationDef, config);
    }

    /** Removing a relationship changes the ontomap; legacy relational sources have no physical edge DDL. */
    default void deleteSchemaRelationship(String name, Map<String, String> relationDef, DataRagConfig config) {
        capabilities().requireSchemaWrite();
    }

    default void addSchemaAttribute(String className, Map<String, Object> attrDef, DataRagConfig config) {
        capabilities().requireSchemaWrite();
        if (useM3()) LegacyM3SchemaOperations.addAttribute(className, attrDef, config);
    }

    default void deleteSchemaAttribute(String className, String attrName, DataRagConfig config) {
        capabilities().requireSchemaWrite();
        if (useM3()) LegacyM3SchemaOperations.deleteAttribute(className, attrName, config);
    }

	/**
	 * Open a new JDBC connection from this adapter's own connection.
	 * Driver choice and URL rewriting stay in each adapter module.
	 */
	Connection openConnection() throws SQLException;

	/**
	 * Test this adapter's connection without touching any saved configuration.
	 * DuckDB overrides it to reject a missing database file before connecting; non-JDBC adapters run their own probe instead.
	 */
	default JSONObject testConnection() throws Exception {
		return new DataAdapterConnectionTester().test(this);
	}

	/**
	 * Whether this adapter's data source expects a date/timestamp condition as epoch milliseconds
	 * instead of the literal value the DSL wrote.
	 *
	 * <p>Default {@code false}: the value is passed through verbatim and compared against the column
	 * natively, so the source's own time convention is never reinterpreted. That is the only safe
	 * default for an existing customer source whose stored time convention is unknown. M3 overrides
	 * this because its store requires epoch millis.</p>
	 */
	default boolean timeConditionsAsEpochMillis() {
		return false;
	}

	/**
	 * The time zone used to turn a literal time condition into epoch milliseconds; only consulted
	 * when {@link #timeConditionsAsEpochMillis()} is true. {@code null} means the process default
	 * (the deployment time zone).
	 */
	default TimeZone timeConditionZone() {
		return null;
	}

	public String executeDsl(String queryJson, String sessionId, DslExecutionMode mode, List<String> generatedMqls) throws Exception;
	
	public String getSampleDataFileName(String sampleDataDirName, String className);
	
	public String querySampleDataByClassName(String className, List<String> attrs);
	
	public void validateClassNameFormat(String className) throws Exception;

	/** Refuse an attribute this data source cannot key a class's objects by. */
	public void validatePrimaryKeyName(String attrName) throws Exception;
	
	public void createNamespace(String namespace);
	
	public void truncateNamespace(String namespace);
	
	public void dropNamespace(String namespace);
	
	public void createClass(String namespace, Map<String, Object> classDef);
	
	public void createEdgeType(String namespace, String edgeName);
	
	public void insertObjects(String namespace, Map<String, Object> classDef, JSONArray objs) throws Exception;
	
	public void updateObjects(String namespace, Map<String, Object> classDef, JSONObject setValues, JSONObject where) throws Exception;
	
	public void deleteObjects(String namespace, Map<String, Object> classDef, JSONObject where) throws Exception;
	
	public void createEdge(String namespace, String relationName, String sourceClassName, String sourceObjId, String targetClassName, String targetObjId) throws Exception;
	
	public void deleteEdge(String namespace, String relationName, String sourceClassName, String sourceObjId, String targetClassName, String targetObjId) throws Exception;
	
	public Map<String, Object> query(JSONObject dsl, String sandboxId, String domainId, VectorResourceDao vectorResourceDao, DataRagConfig dataRagConfig) throws Exception;
	
	/**
	 * Query the current value of a single object's vector attribute.
	 *
	 * <p>A vector attribute is stored as a text column holding the JSON array string
	 * {@code [{"path":"...","text":"..."},...]}. This method returns that array parsed
	 * into a {@link JSONArray} (empty array when the attribute is null/blank).
	 * If the object does not exist, an {@link IllegalArgumentException} is thrown.</p>
	 *
	 * @param classDef the class definition (used to resolve the full class/table name and primary key)
	 * @param attrName the vector attribute name
	 * @param objectId the object's stored id value (M3 uses the {@code class:id} prefixed form,
	 *                 relational adapters use the plain primary-key value) — no prefix handling is done here
	 * @return the current vector attribute value as a JSON array
	 * @throws IllegalArgumentException if the object does not exist
	 */
	public JSONArray queryVectorAttr(Map<String, Object> classDef, String attrName, String objectId) throws Exception;

	/**
	 * Resolve the sandbox-scoped class (table) name for the given plain class name.
	 *
	 * <p>M3 adapters strip the namespace segment from {@code /namespace/ClassName} and return
	 * {@code /sandboxId/ClassName}; relational adapters simply prefix the plain class name
	 * ({@code sandboxId + className}). This mirrors exactly how each adapter names its business
	 * table inside a sandbox, so vector-table names stay aligned with reads.</p>
	 */
	public String sandboxClassName(String sandboxId, String className);

	/**
	 * Resolve the sandbox-scoped object id for the given plain object id.
	 *
	 * <p>M3 adapters prefix the id ({@code sandboxId + objectId}), matching the id value the M3
	 * store actually holds for a sandbox object; relational adapters leave the plain primary-key
	 * value unchanged (they never prefix the id column).</p>
	 */
	public String sandboxObjectId(String sandboxId, String objectId);

	/**
	 * Report the problems this adapter finds in a domain's ontomap under its own routing rules.
	 *
	 * <p>The default returns none: a single-source adapter can honour every relationship its ontomap
	 * declares. An adapter that constrains the ontomap (the multi-source adapter, whose cross-source
	 * relationships are not a graph query) returns the located problems, so the management layer can
	 * report them without knowing any adapter's constraints. Saving a multi-source configuration runs it
	 * against the domain's current ontomap.</p>
	 *
	 * @param jsonRule the domain's ontomap ({@code classDef} / {@code relationship_rule})
	 */
	default List<ConfigurationProblem> validateOntoMap(Map<String, Object> jsonRule) {
		return List.of();
	}

}
