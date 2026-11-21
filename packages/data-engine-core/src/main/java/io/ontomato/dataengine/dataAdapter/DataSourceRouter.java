package io.ontomato.dataengine.dataAdapter;

import java.util.LinkedHashMap;
import java.util.Map;

import io.ontomato.dataengine.config.multisource.ClassRoute;
import io.ontomato.dataengine.config.multisource.MultiDataSourceDefinition;
import io.ontomato.dataengine.config.multisource.RelationshipRoute;

/**
 * Maps an ontology class to the id of the data source it lives in, and an ontology relationship to its
 * physical edge name.
 *
 * <p>Pure lookups over the configured routes: it holds no adapter and touches no data. Because
 * {@code forOntoMap} values are unique (enforced when the configuration is validated), a class name
 * resolves to exactly one source, so no name has to be prefixed per source.</p>
 */
public class DataSourceRouter {

	private final Map<String, String> classToSource = new LinkedHashMap<>();
	private final Map<String, String> physicalEdgeNames = new LinkedHashMap<>();

	public DataSourceRouter(MultiDataSourceDefinition definition) {
		for (ClassRoute route : definition.getClasses()) {
			String key = key(route.getForOntoMap());
			if (key != null && route.getSourceId() != null) {
				classToSource.put(key, route.getSourceId());
			}
		}
		for (RelationshipRoute route : definition.getRelationships()) {
			String key = key(route.getForOntoMap());
			if (key != null && route.getRealEdge() != null && !route.getRealEdge().isBlank()) {
				physicalEdgeNames.put(key, route.getRealEdge().trim());
			}
		}
	}

	/** The source id a class lives in, or {@code null} when unrouted. */
	public String sourceOfClass(String className) {
		return className == null ? null : classToSource.get(key(className));
	}

	/** The physical edge name of a relationship: its configured {@code realEdge}, otherwise its own name. */
	public String physicalEdgeName(String relationshipName) {
		return physicalEdgeNames.getOrDefault(key(relationshipName), relationshipName);
	}

	/**
	 * Normalize a name the way routing compares it: trimmed, with any leading slashes dropped, so
	 * {@code /Person} and {@code Person} route alike. This mirrors {@code DBSchemaUtil.normalizeClassName}'s
	 * slash tolerance without needing the class definitions.
	 */
	static String key(String name) {
		if (name == null) {
			return null;
		}
		String key = name.trim();
		while (key.startsWith("/")) {
			key = key.substring(1);
		}
		return key;
	}
}
