package io.ontomato.dataengine.controller;

import com.alibaba.fastjson2.JSONObject;
import io.ontomato.dataengine.core.bean.User;
import io.ontomato.dataengine.service.IdentityService;
import io.ontomato.dataengine.service.controlled.ControlledActionService;
import io.ontomato.dataengine.service.sys.bean.permission.UserDataPermission;
import io.ontomato.dataengine.util.SystemUtils;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ControlledActionControllerTest {
    @Test void visitorCannotSupplyPrincipalDomainBackendClassOrVersion() throws Exception {
        var service = mock(ControlledActionService.class); var identity = mock(IdentityService.class);
        var permission = new UserDataPermission(); permission.setFullData(true);
        when(identity.getCurrentUserDataPermission()).thenReturn(permission);
        var controller = new ControlledActionController(service, identity); var user = mock(User.class); when(user.getDomainId()).thenReturn("host-domain");
        try (var system = mockStatic(SystemUtils.class)) {
            system.when(SystemUtils::getCurUser).thenReturn(user);
            for (String injected : new String[]{"subject", "roles", "domainId", "url", "className", "expectedEtag", "idempotencyKey"}) {
                var body = JSONObject.of("decisionId", "d1", injected, "spoof");
                assertFalse(controller.invoke("execute", body).getBooleanValue("success"));
            }
            verifyNoInteractions(service);
            when(service.execute("host-domain", "d1")).thenReturn(JSONObject.of("state", "SUCCEEDED"));
            assertTrue(controller.invoke("execute", JSONObject.of("decisionId", "d1")).getBooleanValue("success"));
            verify(service).execute("host-domain", "d1");
        }
    }
    @Test void limitedPermissionProfilesDoNotInheritTheServiceAccount() {
        var service = mock(ControlledActionService.class); var identity = mock(IdentityService.class);
        when(identity.getCurrentUserDataPermission()).thenReturn(new UserDataPermission());
        var user = mock(User.class); when(user.getDomainId()).thenReturn("1");
        try (var system = mockStatic(SystemUtils.class)) {
            system.when(SystemUtils::getCurUser).thenReturn(user);
            var result = new ControlledActionController(service, identity).invoke("catalog", new JSONObject());
            assertEquals("CONTROLLED_ACTION_FORBIDDEN", result.getString("code")); verifyNoInteractions(service);
        }
    }
}
