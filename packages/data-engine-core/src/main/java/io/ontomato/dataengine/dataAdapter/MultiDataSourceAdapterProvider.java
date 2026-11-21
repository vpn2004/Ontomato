package io.ontomato.dataengine.dataAdapter;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import io.ontomato.dataengine.config.BusinessConfig;
import io.ontomato.dataengine.config.DataRagConfig;

/**
 * The {@code multi} data adapter type: one domain spread over several data sources.
 *
 * <p>Installed by the engine itself (it is not a database driver), so it lives in data-engine-core
 * rather than an adapter module. It declares no connection fields — its sources and their connections
 * come from {@code businessConfig.multiDataSource} — and it needs the registry to build its delegates,
 * which is why it overrides the business-config {@code create} instead of the single-connection one.</p>
 */
@Component
public class MultiDataSourceAdapterProvider implements DataAdapterProvider {

	/**
	 * Resolve the registry only when creating a multi-source adapter, after all providers are registered.
	 * Package-private so tests in this package can also wire a registry directly.
	 */
	@Autowired
	@Lazy
	DataAdapterRegistry registry;

	@Override
	public String type() {
		return "multi";
	}

	@Override
	public String label() {
		return "Multi-Source";
	}

	/**
	 * {@code true}: keep the visual modelling dialog offering join fields. A native-graph source ignores
	 * them (its relationships materialize on first use), so the extra inputs are inert there, while a
	 * relational source in the same domain still needs them.
	 */
	@Override
	public boolean requiresJoinFields() {
		return true;
	}

	/** No connection of its own: every source's connection is named in {@code multiDataSource}. */
	@Override
	public List<String> connectionFields() {
		return List.of();
	}

	@Override
	public Map<String, String> connectionExamples() {
		return Map.of();
	}

	@Override
	public DataAdapter create(DataAdapterConnection connection, DataRagConfig dataRagConfig, Map<String, Object> jsonRule) {
		throw new UnsupportedOperationException(
				"The multi data adapter is configured through BusinessConfig; use create(BusinessConfig, DataRagConfig, jsonRule)");
	}

	@Override
	public DataAdapter create(BusinessConfig businessConfig, DataRagConfig dataRagConfig, Map<String, Object> jsonRule) {
		return new MultiDataSourceAdapter(registry, businessConfig, dataRagConfig, jsonRule);
	}
}
