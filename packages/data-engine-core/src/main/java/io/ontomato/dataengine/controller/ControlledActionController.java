package io.ontomato.dataengine.controller;

import com.alibaba.fastjson2.JSONObject;
import io.ontomato.dataengine.service.IdentityService;
import io.ontomato.dataengine.service.controlled.ControlledActionService;
import io.ontomato.dataengine.util.SystemUtils;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

/** The host resolves identity. No role, datasource, class or ETag supplied by visitors is trusted. */
@RestController
public class ControlledActionController {
    private final ControlledActionService actions;
    private final IdentityService identity;
    public ControlledActionController(ControlledActionService actions, IdentityService identity) { this.actions = actions; this.identity = identity; }
    @PostMapping("/action/controlled/{operation}")
    public JSONObject invoke(@PathVariable String operation, @RequestBody JSONObject body) {
        try {
            var user = SystemUtils.getCurUser();
            var permission = identity.getCurrentUserDataPermission();
            // Limited row/field permission profiles need an explicit policy bridge before using a shared service account.
            if (permission == null || !permission.isFullData()) throw new SecurityException("Controlled Actions require a supported host permission profile");
            Set<String> allowed = switch (operation) {
                case "catalog" -> Set.of();
                case "prepare" -> Set.of("actionId", "objectId", "values");
                case "execute", "status" -> Set.of("decisionId");
                default -> throw new IllegalArgumentException("Unknown controlled Action operation");
            };
            if (!allowed.equals(body.keySet())) throw new IllegalArgumentException("Unexpected or missing controlled Action parameters");
            Object result = switch (operation) {
                case "catalog" -> actions.catalog(user.getDomainId());
                case "prepare" -> actions.prepare(user.getDomainId(), "shared-visitor", body.getString("actionId"), body.getString("objectId"), body.getJSONObject("values"));
                case "execute" -> actions.execute(user.getDomainId(), body.getString("decisionId"));
                default -> actions.status(user.getDomainId(), body.getString("decisionId"));
            };
            return JSONObject.of("success", true, "data", result);
        } catch (SecurityException error) {
            return JSONObject.of("success", false, "code", "CONTROLLED_ACTION_FORBIDDEN", "message", error.getMessage());
        } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException error) {
            return JSONObject.of("success", false, "code", "CONTROLLED_ACTION_NOT_READY", "message", error.getMessage());
        } catch (Exception error) {
            return JSONObject.of("success", false, "code", "CONTROLLED_ACTION_UNAVAILABLE", "message", "Controlled Action could not be completed; inspect its persisted status before retrying");
        }
    }
}
