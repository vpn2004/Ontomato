package io.ontomato.dataengine.dataAdapter;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class OnnCoreAdapterProviderTest {
    @Test void serverGatewaySecretCannotBeForwardedToAClientSelectedOrigin() {
        var provider = new OnnCoreAdapterProvider();
        ReflectionTestUtils.setField(provider, "trustedUrl", "http://onn:18081");
        ReflectionTestUtils.setField(provider, "gatewayToken", "private-fixture-token");
        ReflectionTestUtils.setField(provider, "readSubject", "ontomato-reader");
        ReflectionTestUtils.setField(provider, "readRoles", "logistics-reader");
        var connection = new DataAdapterConnection(); connection.setUrl("http://attacker:8080");
        assertThrows(IllegalArgumentException.class, () -> provider.create(connection, null, null));
        connection.setUrl("http://onn:18081"); connection.setToken("obsolete-public-token");
        assertNotNull(provider.create(connection, null, null));
        assertEquals("obsolete-public-token", connection.getToken());
    }
    @Test void halfConfiguredReadIdentityFailsClosed() {
        var provider = new OnnCoreAdapterProvider(); ReflectionTestUtils.setField(provider, "readSubject", "reader");
        var connection = new DataAdapterConnection(); connection.setUrl("http://onn:18081");
        assertThrows(IllegalArgumentException.class, () -> provider.create(connection, null, null));
    }
}
