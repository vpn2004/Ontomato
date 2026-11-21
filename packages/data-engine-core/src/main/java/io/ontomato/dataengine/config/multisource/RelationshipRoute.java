package io.ontomato.dataengine.config.multisource;

import lombok.Data;

/**
 * The physical edge name of one ontology relationship inside its data source.
 *
 * <p>Ontology relationship names are unique across the whole domain, while two databases may each
 * hold an edge of the same physical name (both graphs have {@code KNOWS}); the ontology then has to
 * name them apart ({@code knows_a}, {@code knows_b}) and this route maps each back to its physical
 * name. The source a relationship lives in is not configured here: it is the source of its endpoint
 * classes, so that fact has one owner.</p>
 */
@Data
public class RelationshipRoute {

	/** The ontology relationship name, unique across routes. */
	private String forOntoMap;

	/** The physical edge name inside the relationship's source; blank means the ontology name itself. */
	private String realEdge;
}
