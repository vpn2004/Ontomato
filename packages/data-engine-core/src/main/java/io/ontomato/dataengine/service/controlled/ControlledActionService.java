package io.ontomato.dataengine.service.controlled;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter.Feature;
import io.ontomato.dataengine.config.BusinessConfig;
import io.ontomato.dataengine.dao.ControlledActionDecisionDao;
import io.ontomato.dataengine.dao.JSONRuleDao;
import io.ontomato.dataengine.dataAdapter.*;
import io.ontomato.dataengine.service.BusinessConfigService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;

/** Declarative exact-object Actions; legacy Python and arbitrary predicate writes stay closed. */
@Service
public class ControlledActionService {
    private final ControlledActionPolicy policy;
    private final ControlledActionDecisionDao decisions;
    private final BusinessConfigService configs;
    private final JSONRuleDao rules;
    private final DataAdapterRegistry adapters;
    public ControlledActionService(ControlledActionPolicy policy, ControlledActionDecisionDao decisions,
            BusinessConfigService configs, JSONRuleDao rules, DataAdapterRegistry adapters) {
        this.policy = policy; this.decisions = decisions; this.configs = configs; this.rules = rules; this.adapters = adapters;
    }
    public JSONObject catalog(String domain) throws Exception {
        var configured = policy.load(domain);
        if (configured.isEmpty()) return JSONObject.of("enabled", false, "actions", List.of(), "history", List.of());
        var p = configured.get();
        return JSONObject.of("enabled", true, "identityMode", "shared-application-account", "subject", p.principal().subject(),
            "actions", p.definitions().values().stream().map(ControlledActionPolicy.Definition::publicView).toList(),
            "history", decisions.recent(domain).stream().map(id -> publicView(decisions.get(domain, id))).toList());
    }
    public JSONObject prepare(String domain, String requester, String actionId, String objectId, JSONObject values) throws Exception {
        var p = enabled(domain); var definition = p.definition(actionId); var patch = definition.validate(values);
        if (objectId == null || objectId.isBlank() || objectId.length() > 512 || objectId.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Supply a valid exact object id");
        if (decisions.hasUncertain(domain, definition.className(), objectId))
            throw new IllegalStateException("Reconcile the existing uncertain decision for this object before preparing another change");
        var context = context(domain, p, definition);
        var before = context.writer().readVersionedObject(definition.className(), objectId, p.principal());
        String id = UUID.randomUUID().toString();
        var decision = JSONObject.of("id", id, "actionId", actionId, "actionName", definition.name(), "className", definition.className(),
            "objectId", objectId, "values", patch, "before", JSONObject.of(definition.field(), before.getJSONObject("object").get(definition.field())),
            "expectedEtag", before.getString("etag"), "expectedVersion", before.get("version"), "idempotencyKey", "action:" + id,
            "contextHash", context.hash(), "subject", p.principal().subject(), "roles", p.principal().roles(),
            "requester", requester, "identityMode", "shared-application-account", "createdAt", System.currentTimeMillis());
        decisions.insert(domain, decision);
        return publicView(decisions.get(domain, id));
    }
    public JSONObject execute(String domain, String id) throws Exception {
        var decision = decisions.get(domain, id);
        String state = decision.getString("state");
        if (Set.of("SUCCEEDED", "CONFLICT", "REJECTED").contains(state)) return publicView(decision);
        var p = enabled(domain); var definition = p.definition(decision.getString("actionId"));
        var context = context(domain, p, definition);
        if (!Objects.equals(context.hash(), decision.getString("contextHash")))
            throw new IllegalStateException("Action policy, mapping or backend changed; prepare a new decision");
        // This window is shorter than the backend's 24-hour idempotency retention. Never revive old unknown writes.
        if (System.currentTimeMillis() - decision.getLongValue("createdAt") > 30 * 60 * 1000)
            throw new IllegalStateException("Decision expired; reconcile uncertain outcomes before making another decision");
        String claim = UUID.randomUUID().toString();
        if (!decisions.claim(domain, id, claim, System.currentTimeMillis())) return publicView(decisions.get(domain, id));
        try {
            var result = context.writer().patchObject(decision.getString("className"), decision.getString("objectId"),
                definition.validate(decision.getJSONObject("values")), decision.getString("expectedEtag"),
                decision.getString("idempotencyKey"), p.principal());
            decisions.finish(domain, id, claim, "SUCCEEDED", result);
        } catch (ConditionalObjectWrites.Rejected rejected) {
            String outcome = rejected.status() == 412 ? "CONFLICT"
                : Set.of(400, 401, 403, 404, 405, 422).contains(rejected.status()) ? "REJECTED" : "UNKNOWN";
            decisions.finish(domain, id, claim, outcome, JSONObject.of("code", rejected.code(), "httpStatus", rejected.status()));
        } catch (Exception uncertain) {
            // Includes disconnects and malformed post-commit responses. Preserve the original key, ETag and patch.
            decisions.finish(domain, id, claim, "UNKNOWN", JSONObject.of("code", "OUTCOME_REQUIRES_RECONCILIATION"));
        }
        return publicView(decisions.get(domain, id));
    }
    public JSONObject status(String domain, String id) { return publicView(decisions.get(domain, id)); }
    private ControlledActionPolicy.Snapshot enabled(String domain) throws Exception {
        return policy.load(domain).orElseThrow(() -> new UnsupportedOperationException("Controlled Actions are disabled"));
    }
    private record Context(ConditionalObjectWrites writer, String hash) {}
    private Context context(String domain, ControlledActionPolicy.Snapshot p, ControlledActionPolicy.Definition definition) throws Exception {
        BusinessConfig config = configs.get(domain);
        if (!"onn-core".equals(config.getDataAdapter())) throw new UnsupportedOperationException("Controlled Action datasource is not ONN Core");
        var connection = config.getDataAdapterConnections().get("onn-core");
        if (connection == null || !Objects.equals(connection.getUrl(), p.backendUrl()))
            throw new IllegalStateException("Controlled Action backend does not match the server policy");
        Map<String, Object> mapping = rules.query(domain);
        if (mapping == null || mapping.isEmpty()) throw new IllegalStateException("Controlled Actions require a reviewed ontology mapping");
        var adapter = adapters.create(config, mapping);
        if (!(adapter instanceof ConditionalObjectWrites writer)) throw new UnsupportedOperationException("Adapter has no conditional write API");
        var fingerprint = JSONObject.of("domain", domain, "url", p.backendUrl(), "token", connection.getToken(), "mapping", mapping,
            "subject", p.principal().subject(), "roles", p.principal().roles(), "definition", definition.publicView());
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(JSON.toJSONString(canonical(fingerprint), Feature.WriteMapNullValue).getBytes(StandardCharsets.UTF_8)));
        return new Context(writer, hash);
    }
    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>(); map.forEach((k, v) -> sorted.put(k.toString(), canonical(v))); return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(ControlledActionService::canonical).toList();
        return value;
    }
    private static JSONObject publicView(JSONObject decision) {
        var view = new JSONObject(decision);
        for (String key : List.of("expectedEtag", "idempotencyKey", "contextHash", "roles")) view.remove(key);
        return view;
    }
}
