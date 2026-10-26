package io.ontomato.dataengine.dataAdapter;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import io.ontomato.dataengine.config.DataRagConfig;

@Component
public class OnnCoreAdapterProvider implements DataAdapterProvider {
    public String type() { return "onn-core"; }
    public String label() { return "ONN Core"; }
    public boolean requiresJoinFields() { return false; }
    public DataAdapterCapabilities capabilities() { return DataAdapterCapabilities.onnReadOnly(); }
    public List<String> connectionFields() { return List.of("url"); }
    public Map<String, String> connectionExamples() { return Map.of("url", "http://host:18081"); }
    public DataAdapter create(DataAdapterConnection connection, DataRagConfig config, Map<String, Object> ontology) {
        return new OnnCoreAdapter(connection, ontology);
    }
}
