package io.ontomato.dataengine.dataAdapter;

import java.util.List;
import java.util.Map;

import io.ontomato.dataengine.config.BusinessConfig;
import io.ontomato.dataengine.config.DataRagConfig;

/**
 * One installed data adapter type, contributed by a Spring component.
 * The application pom selects modules, each of which can contribute multiple types.
 */
public interface DataAdapterProvider {

	/** Product capabilities independent of the source query language. */
	default DataAdapterCapabilities capabilities() {
		return DataAdapterCapabilities.legacy();
	}

	/** The saved and API value, e.g. "postgresql". */
	String type();

	/** Display name, e.g. "PostgreSQL". */
	String label();

	/** Whether relations name the source and target join fields (fromField/toField) this adapter joins on. */
	boolean requiresJoinFields();

	/** Connection fields the adapter reads, each one of "url", "company", "user", "password". */
	List<String> connectionFields();

	/** Input hints shown for connection fields, field name to example value; fields without a hint are absent. */
	Map<String, String> connectionExamples();

	/** jsonRule may be null for operations that do not translate DSL. */
	DataAdapter create(DataAdapterConnection connection, DataRagConfig dataRagConfig, Map<String, Object> jsonRule);

	/**
	 * Create the adapter of one business config, reading whatever it needs beyond a single connection.
	 *
	 * <p>The default keeps the historical shape — the connection saved under this type, and nothing else —
	 * so every existing adapter is unaffected. A type that needs more than one connection (the multi-source
	 * adapter) overrides this rather than guessing a connection out of a single value.</p>
	 */
	default DataAdapter create(BusinessConfig businessConfig, DataRagConfig dataRagConfig, Map<String, Object> jsonRule) {
		DataAdapterConnection connection = businessConfig.getDataAdapterConnections()
				.getOrDefault(type(), new DataAdapterConnection());
		return create(connection, dataRagConfig, jsonRule);
	}
}
