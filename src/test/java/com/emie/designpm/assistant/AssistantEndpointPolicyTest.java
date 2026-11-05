package com.emie.designpm.assistant;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class AssistantEndpointPolicyTest {
    private static final String LAN_BASE = "http://192.168.200.118:8317/v1";

    @Test
    void permitsOnlyTheDeploymentApprovedLanService() {
        var policy = new AssistantEndpointPolicy(LAN_BASE);
        assertDoesNotThrow(() -> policy.validate(LAN_BASE));
        assertDoesNotThrow(() -> policy.validate(LAN_BASE + "/"));
        assertDoesNotThrow(() -> policy.validate("HTTP://192.168.200.118:8317/v1"));
        for (String other : List.of(
                "http://192.168.200.119:8317/v1",
                "http://192.168.200.118:8318/v1",
                "http://192.168.200.118/v1",
                "http://192.168.200.118:8317/v2",
                "http://192.168.200.118:8317/v1/models",
                "http://192.168.200.118:8317/v10",
                "https://192.168.200.118:8317/v1"))
            assertThrows(IllegalArgumentException.class, () -> policy.validate(other));
    }

    @Test
    void keepsThePublicHttpsRuleAndDefaultsToNoLanExceptions() {
        var policy = new AssistantEndpointPolicy("");
        assertDoesNotThrow(() -> policy.validate("https://1.1.1.1/v1"));
        assertDoesNotThrow(() -> policy.validate("https://1.1.1.1:443/v1"));
        for (String other : List.of(
                LAN_BASE,
                "https://192.168.200.118/v1",
                "http://1.1.1.1/v1",
                "https://1.1.1.1:8443/v1",
                "https://127.0.0.1/v1",
                "https://169.254.169.254/v1",
                "https://100.100.100.200/v1",
                "https://[fd00::1]/v1")) assertThrows(IllegalArgumentException.class, () -> policy.validate(other));
    }

    @Test
    void supportsMultipleExactServicesAndCanonicalDefaultPorts() {
        var policy = new AssistantEndpointPolicy(LAN_BASE + ", https://10.2.3.4:443/proxy/v1/,http://172.16.0.2:80");
        for (String base : List.of(
                LAN_BASE,
                "https://10.2.3.4/proxy/v1",
                "https://10.2.3.4:443/proxy/v1/",
                "http://172.16.0.2/",
                "http://172.16.0.2:80")) assertDoesNotThrow(() -> policy.validate(base));
        assertThrows(IllegalArgumentException.class, () -> policy.validate("http://10.2.3.4/proxy/v1"));
        assertThrows(IllegalArgumentException.class, () -> policy.validate("http://172.16.0.2:8080"));
    }

    @Test
    void invalidDeploymentEntriesNeverEnableBroaderNetworkAccessOrExposeTheirValue() {
        for (String base : List.of(
                "http://localhost:8317/v1",
                "http://model.internal:8317/v1",
                "http://127.0.0.1:8317/v1",
                "http://169.254.169.254/v1",
                "http://0.0.0.0:8317/v1",
                "http://224.0.0.1/v1",
                "http://1.1.1.1/v1",
                "http://100.64.0.1/v1",
                "http://[fd00::1]:8317/v1",
                "http://192.168.200.0118:8317/v1",
                "http://192.168.200.118:0/v1",
                "http://192.168.200.118:65536/v1",
                "http://192.168.200.118:/v1",
                "http://192.168.200.118:08317/v1",
                "http://secret@192.168.200.118:8317/v1",
                LAN_BASE + "?key=secret",
                LAN_BASE + "#secret",
                LAN_BASE + "/../secret",
                LAN_BASE + "/./secret",
                LAN_BASE + "/%2e%2e/secret",
                LAN_BASE + "/%2fsecret",
                LAN_BASE + "\\secret",
                LAN_BASE + "\nsecret",
                LAN_BASE + ",")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> new AssistantEndpointPolicy(base));
            assertFalse(failure.getMessage().contains(base));
            assertFalse(failure.getMessage().contains("secret"));
            assertNull(failure.getCause());
        }
    }

    @Test
    void trustedRequestsCannotAddCredentialsQueriesOrAlternatePaths() {
        var policy = new AssistantEndpointPolicy(LAN_BASE);
        for (String base : List.of(
                "http://secret@192.168.200.118:8317/v1",
                LAN_BASE + "?key=secret",
                LAN_BASE + "#secret",
                "http://192.168.200.118:8317/x/../v1",
                "http://192.168.200.118:8317/%76%31",
                "http://192.168.200.118:8317//v1",
                "http://192.168.200.118:8317/v1/.")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> policy.validate(base));
            assertFalse(failure.getMessage().contains("secret"));
            assertNull(failure.getCause());
        }
    }
}
