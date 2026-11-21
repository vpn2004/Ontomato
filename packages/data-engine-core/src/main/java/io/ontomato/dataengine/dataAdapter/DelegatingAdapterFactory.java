package io.ontomato.dataengine.dataAdapter;

import java.util.Map;

import io.ontomato.dataengine.config.DataRagConfig;
import io.ontomato.dataengine.config.multisource.DataSourceEntry;

/**
 * Builds the delegate adapter of one configured source on demand.
 *
 * <p>A delegate is the ordinary adapter of the source's type, built over the source's own connection;
 * two sources of one type are two instances of the same implementation. Delegates are deliberately not
 * cached across calls: an adapter is a thin holder of {@code (connection, jsonRule)}, and different call
 * paths hand the same source different jsonRules, so a cache keyed on the source id would eventually
 * serve a stale ontomap. Building is cheap, so it happens per call.</p>
 */
public class DelegatingAdapterFactory {

	private final DataAdapterRegistry registry;
	private final DataRagConfig dataRagConfig;

	public DelegatingAdapterFactory(DataAdapterRegistry registry, DataRagConfig dataRagConfig) {
		this.registry = registry;
		this.dataRagConfig = dataRagConfig;
	}

	/** The delegate of one validated source, wired to the given jsonRule (the source's own view of the ontomap). */
	public DataAdapter create(DataSourceEntry source, Map<String, Object> jsonRule) {
		return registry.provider(source.getAdapterType()).create(source.getConnection(), dataRagConfig, jsonRule);
	}
}
