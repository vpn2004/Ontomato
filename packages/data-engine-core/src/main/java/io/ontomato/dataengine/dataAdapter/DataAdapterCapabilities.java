package io.ontomato.dataengine.dataAdapter;

/** Operations exposed by a business-data adapter, independent of its query language. */
public record DataAdapterCapabilities(boolean query, boolean schemaRead, boolean schemaWrite,
        boolean objectWrite, boolean edgeWrite, boolean sandbox, boolean graphRead) {

    public static DataAdapterCapabilities legacy() {
        return new DataAdapterCapabilities(true, false, true, true, true, true, false);
    }

    public static DataAdapterCapabilities onnReadOnly() {
        return new DataAdapterCapabilities(true, true, false, false, false, false, false);
    }

    public void requireQuery() { require(query, "queries"); }
    public void requireSchemaRead() { require(schemaRead, "schema reads"); }
    public void requireSchemaWrite() { require(schemaWrite, "schema writes"); }
    public void requireObjectWrite() { require(objectWrite, "object writes"); }
    public void requireEdgeWrite() { require(edgeWrite, "edge writes"); }
    public void requireSandbox() { require(sandbox, "sandbox operations"); }
    public void requireGraphRead() { require(graphRead, "graph reads"); }

    private static void require(boolean supported, String operation) {
        if (!supported) {
            throw new UnsupportedOperationException("The selected data adapter does not support " + operation);
        }
    }
}
