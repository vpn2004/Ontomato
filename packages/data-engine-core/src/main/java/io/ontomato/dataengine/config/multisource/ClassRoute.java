package io.ontomato.dataengine.config.multisource;

import lombok.Data;

/**
 * Routes one ontology object class to one data source.
 *
 * <p>{@code forOntoMap} is the {@code classDef.className} and must be unique across the whole
 * definition, so a class never belongs to two sources at once (there is no union semantics and no
 * routing ambiguity). This is enforced by code, not by convention.</p>
 */
@Data
public class ClassRoute {

	/** The ontology class name ({@code classDef.className}), globally unique across sources. */
	private String forOntoMap;

	/** The {@link DataSourceEntry#getId()} this class lives in. */
	private String sourceId;
}
