package io.ontomato.dataengine.dataAdapter;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.*;

/** Narrow conditional-write transport; no automatic retries and no implicit principal/role. */
final class OnnCoreConditionalWriter {
    private final OnnCoreHttpClient client;
    private final Map<String, Object> mapping;

    OnnCoreConditionalWriter(OnnCoreHttpClient client, Map<String, Object> mapping, ConditionalObjectWrites.Principal principal) {
        this.client = client.asPrincipal(Objects.requireNonNull(principal, "Missing trusted write principal"));
        this.mapping = mapping;
    }

    JSONObject read(String className, String id) throws Exception {
        identity(className, id); requireReady(client.capabilities());
        OnnCoreSchema schema = new OnnCoreSchema(client.schema()); schema.validateMapping(mapping, className, Set.of("id"));
        JSONObject instance = client.instance(className, id);
        if (instance == null) throw new IllegalStateException("ONN conditional write target does not exist");
        JSONObject flattened = schema.flatten(className, instance);
        if (!id.equals(flattened.getString("id"))) throw new IllegalStateException("ONN versioned read returned a mismatched identity");
        for (String field : schema.disabledSampleFields(mapping, className)) flattened.remove(field);
        schema.validateMapping(mapping, className, flattened.keySet());
        String etag = instance.getString("etag"); etag(etag);
        if (!positiveVersion(instance.get("version")))
            throw new IllegalStateException("ONN versioned read omitted its object version");
        return JSONObject.of("object", flattened, "version", instance.get("version"), "etag", etag);
    }

    JSONObject patch(String className, String id, JSONObject values, String expectedEtag, String idempotencyKey) throws Exception {
        identity(className, id); etag(expectedEtag);
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{1,128}"))
            throw new IllegalArgumentException("Supply a stable idempotency key for this write decision");
        if (values == null || values.isEmpty() || values.containsKey("id"))
            throw new IllegalArgumentException("Conditional PATCH requires nonempty scalar attributes; identity is immutable");
        requireReady(client.capabilities());
        OnnCoreSchema schema = new OnnCoreSchema(client.schema()); schema.validateMapping(mapping, className, values.keySet());
        JSONObject attributes = new JSONObject();
        for (String field : values.keySet()) {
            String source = schema.sourceField(className, field);
            Object value = values.get(field);
            if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean))
                throw new IllegalArgumentException("Conditional PATCH supports scalar attributes only");
            // The backend remains authoritative for type/constraint validation. Never cast text to numbers or dates.
            attributes.put(source, value);
        }
        JSONObject result = client.patch(className, id, attributes, expectedEtag, idempotencyKey);
        // A timeout/disconnect or invalid response can follow a commit: caller must retain the same
        // key and original ETag while reconciling. This layer never retries or changes the decision.
        JSONArray updated = result.getJSONArray("updated_attributes");
        if (!"ok".equals(result.getString("status")) || !positiveVersion(result.get("version")) || updated == null
            || updated.size() != attributes.size() || !new HashSet<>(updated).equals(attributes.keySet()))
            throw new IllegalStateException("ONN write outcome needs reconciliation with the original idempotency key");
        etag(result.getString("etag"));
        return result;
    }

    static void requireReady(JSONObject wire) {
        if (!"v1".equals(wire.getString("api_contract_version")) || !"scoped_policy".equals(wire.getString("authorization_mode")))
            throw new UnsupportedOperationException("ONN conditional writes require scoped authorization");
        // Reuse strict validation of duplicate features and Boolean enabled claims.
        OnnCoreCapabilities.parse(wire);
        Map<String, String> required = Map.of("conditional_write", "v2", "retry_idempotency", "v2", "audit_trail", "v1",
            "action_governance", "v1", "resource_governance", "v1");
        JSONArray features = wire.getJSONArray("features");
        for (Map.Entry<String, String> entry : required.entrySet()) {
            JSONObject found = features.stream().map(JSONObject::from).filter(f -> entry.getKey().equals(f.getString("id"))).findFirst().orElse(null);
            if (found == null || !Boolean.TRUE.equals(found.get("enabled")) || !entry.getValue().equals(found.getString("contract_version")))
                throw new UnsupportedOperationException("ONN conditional write capability is unavailable: " + entry.getKey());
        }
    }

    private static void identity(String className, String id) {
        OnnCoreSchema.validatePath(className);
        if (id == null || id.isBlank() || id.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Missing or invalid ONN object id");
    }
    private static void etag(String etag) {
        if (etag == null || !etag.matches("\"[A-Za-z0-9._:-]{1,512}\""))
            throw new IllegalArgumentException("Supply the complete opaque strong ETag obtained from the versioned object read");
    }
    private static boolean positiveVersion(Object value) {
        try { return value instanceof Number && new java.math.BigDecimal(value.toString()).longValueExact() > 0; }
        catch (RuntimeException error) { return false; }
    }
}
