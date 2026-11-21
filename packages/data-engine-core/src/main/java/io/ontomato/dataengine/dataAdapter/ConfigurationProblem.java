package io.ontomato.dataengine.dataAdapter;

/**
 * One located problem in a domain's data adapter configuration or in its ontomap under that adapter's
 * rules.
 *
 * <p>An adapter reports what it cannot honour rather than the management layer knowing any adapter's
 * constraints: the default implementation of {@link DataAdapter#validateOntoMap} returns none, and an
 * adapter that constrains the ontomap (for example the multi-source adapter, whose cross-source
 * relationships cannot be a graph query) returns the located problems. The multi-source definition
 * check reports in the same shape, so a save can answer with every problem at once.</p>
 *
 * @param path   a locator, e.g. {@code relationship_rule[person_knows]} or {@code dataSources[crm]}
 * @param detail a human-readable sentence naming the offending construct
 */
public record ConfigurationProblem(String path, String detail) {
}
