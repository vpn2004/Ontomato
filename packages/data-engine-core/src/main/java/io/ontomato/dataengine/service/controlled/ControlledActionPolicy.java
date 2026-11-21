package io.ontomato.dataengine.service.controlled;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.ontomato.dataengine.dataAdapter.ConditionalObjectWrites.Principal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Server-owned allowlist. It is never supplied by the browser or the saved datasource connection. */
@Component
public class ControlledActionPolicy {
    private final String file;
    public ControlledActionPolicy(@Value("${ONTOMATO_CONTROLLED_ACTION_POLICY:}") String file) { this.file = file; }

    public record Definition(String id, String name, String className, String field, String fieldLabel, List<String> values) {
        public JSONObject publicView() {
            return JSONObject.of("id", id, "name", name, "className", className, "field", field,
                "fieldLabel", fieldLabel, "values", values);
        }
        public JSONObject validate(JSONObject valuesToWrite) {
            if (valuesToWrite == null || valuesToWrite.size() != 1 || !valuesToWrite.containsKey(field)
                || !(valuesToWrite.get(field) instanceof String value) || !values.contains(value))
                throw new IllegalArgumentException("Only the configured field and enum values may be changed");
            return JSONObject.of(field, valuesToWrite.getString(field));
        }
    }
    public record Snapshot(String domainId, String backendUrl, Principal principal, Map<String, Definition> definitions) {
        public Definition definition(String id) {
            var value = definitions.get(id);
            if (value == null) throw new IllegalArgumentException("Controlled Action is not enabled");
            return value;
        }
    }
    public Optional<Snapshot> load(String domainId) throws Exception {
        if (file == null || file.isBlank()) return Optional.empty();
        Path path = Path.of(file);
        if (Files.size(path) > 65536) throw new IllegalStateException("Invalid controlled Action policy size");
        JSONObject policy = JSON.parseObject(Files.readString(path));
        if (!Integer.valueOf(1).equals(policy.getInteger("version"))
            || !Boolean.TRUE.equals(policy.get("allowSharedVisitors")))
            throw new IllegalStateException("Explicit shared application account authorization is required");
        if (!Objects.equals(domainId, policy.getString("domainId"))) return Optional.empty();
        var roles = policy.getJSONArray("roles");
        if (roles == null) throw new IllegalStateException("Missing controlled Action roles");
        Principal principal = new Principal(policy.getString("subject"), roles.toJavaList(String.class));
        var definitions = new LinkedHashMap<String, Definition>();
        var actions = policy.getJSONArray("actions");
        if (actions == null || actions.isEmpty() || actions.size() > 20) throw new IllegalStateException("Invalid Action allowlist");
        for (int i = 0; i < actions.size(); i++) {
            var a = actions.getJSONObject(i);
            String id = a.getString("id"), cls = a.getString("className"), field = a.getString("field");
            if (id == null || !id.matches("[a-z][a-z0-9-]{0,79}") || cls == null || !cls.startsWith("/")
                || field == null || !field.matches("[A-Za-z_][A-Za-z0-9_]{0,79}") || "id".equals(field))
                throw new IllegalStateException("Invalid controlled Action definition");
            List<String> values = a.getJSONArray("values").toJavaList(String.class);
            if (values.isEmpty() || values.size() > 100 || values.stream().anyMatch(v -> v == null || v.isBlank() || v.length() > 200))
                throw new IllegalStateException("Invalid controlled Action enum");
            Definition definition = new Definition(id, Objects.requireNonNull(a.getString("name")), cls, field,
                Objects.requireNonNull(a.getString("fieldLabel")), List.copyOf(values));
            if (definitions.putIfAbsent(id, definition) != null) throw new IllegalStateException("Duplicate Action id");
        }
        String url = policy.getString("backendUrl");
        if (url == null || url.isBlank()) throw new IllegalStateException("Missing pinned Action backend");
        return Optional.of(new Snapshot(domainId, url, principal, Collections.unmodifiableMap(definitions)));
    }
}
