package io.ontomato.dataengine.config.multisource;

import io.ontomato.dataengine.dataAdapter.DataAdapterConnection;
import lombok.Data;

/**
 * One data source a multi-source domain reads from.
 *
 * <p>The source owns its connection. Several sources may share one adapter type (two PostgreSQL
 * databases), so a connection cannot be keyed by type; {@code BusinessConfig.dataAdapterConnections}
 * serves the single-source mode only, and switching modes never overwrites the other mode's values.</p>
 */
@Data
public class DataSourceEntry {

	/** Stable id the class routes point at. */
	private String id;

	/** Display name shown in management UIs. */
	private String label;

	/** The installed data adapter type this source uses, e.g. {@code postgresql}. Never {@code multi}. */
	private String adapterType;

	/**
	 * A read-only source is skipped by namespace fan-out; its own adapter still refuses writes with
	 * {@code UnsupportedDataAdapterOperationException}, so this only avoids a guaranteed failure.
	 */
	private boolean readOnly;

	/** The connection of this source; only the fields its adapter type declares are kept on save. */
	private DataAdapterConnection connection = new DataAdapterConnection();
}
