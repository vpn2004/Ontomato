package io.ontomato.dataengine.dataAdapter;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import io.ontomato.dataengine.config.DataRagConfig;

@Component
public class OnnCoreAdapterProvider implements DataAdapterProvider {
    @Value("${ONTOMATO_ONN_READ_SUBJECT:}") private String readSubject = "";
    @Value("${ONTOMATO_ONN_READ_ROLES:}") private String readRoles = "";
    @Value("${ONTOMATO_ONN_TRUSTED_URL:}") private String trustedUrl = "";
    @Value("${ONTOMATO_ONN_GATEWAY_TOKEN:}") private String gatewayToken = "";
    public String type() { return "onn-core"; }
    public String label() { return "ONN Core"; }
    public boolean requiresJoinFields() { return false; }
    public DataAdapterCapabilities capabilities() { return DataAdapterCapabilities.onnReadOnly(); }
    public List<String> connectionFields() { return List.of("url", "token"); }
    public Map<String, String> connectionExamples() { return Map.of("url", "http://host:18081", "token", "trusted gateway token"); }
    public DataAdapter create(DataAdapterConnection connection, DataRagConfig config, Map<String, Object> ontology) {
        if (!trustedUrl.isBlank() || !gatewayToken.isBlank()) {
            if (gatewayToken.isBlank() || !trustedUrl.equals(connection.getUrl()))
                throw new IllegalArgumentException("The ONN datasource must match the server's trusted gateway origin");
            var trusted = new DataAdapterConnection(); trusted.setUrl(trustedUrl); trusted.setToken(gatewayToken);
            connection = trusted;
        }
        ConditionalObjectWrites.Principal reader = null;
        if (!readSubject.isBlank() || !readRoles.isBlank())
            reader = new ConditionalObjectWrites.Principal(readSubject, List.of(readRoles.split(",")));
        return new OnnCoreAdapter(connection, ontology, reader);
    }
}
