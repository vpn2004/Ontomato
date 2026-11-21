package io.ontomato.dataengine.config.multisource;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * The multi-source routing configuration of one domain.
 *
 * <p>Unset ({@code null}) on a {@code BusinessConfig} means a single-source domain and the historical
 * behaviour: {@code DataAdapterRegistry.create} returns the one adapter of {@code dataAdapter}. Setting
 * {@code dataAdapter = "multi"} plus this block switches the domain to routed multi-source mode.</p>
 *
 * <p>It lives in the business config rather than in the ontomap because where a class's data lives is a
 * deployment fact, not ontology meaning: the same ontomap can be served from different sources. A
 * relationship's source is not stored here; it follows from its endpoint classes in the ontomap.</p>
 */
@Data
public class MultiDataSourceDefinition {

	/** The sources this domain reads from. */
	private List<DataSourceEntry> dataSources = new ArrayList<>();

	/** Class routes; {@code forOntoMap} values must be unique. */
	private List<ClassRoute> classes = new ArrayList<>();

	/** Physical edge names; {@code forOntoMap} values must be unique. Only needed where a physical name differs. */
	private List<RelationshipRoute> relationships = new ArrayList<>();

	/** Convenience: the source with this id, or {@code null}. */
	public DataSourceEntry source(String sourceId) {
		if (sourceId == null || dataSources == null) {
			return null;
		}
		for (DataSourceEntry source : dataSources) {
			if (sourceId.equals(source.getId())) {
				return source;
			}
		}
		return null;
	}
}
