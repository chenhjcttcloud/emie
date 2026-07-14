package com.emie.designpm;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationSecurityTest {

    @Test
    void repositoryConfigurationDoesNotProvideConnectableDatabaseDefaults() throws IOException {
        String config;
        try (InputStream input = getClass().getResourceAsStream("/application.yml")) {
            assertTrue(input != null, "application.yml 必须存在");
            config = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(config.contains("jdbc:h2:file:./data/designpm-dev"));
        assertTrue(config.contains("ddl-auto: validate"));
        assertFalse(config.matches("(?s).*\\$\\{DESIGNPM_DB_PASSWORD:[^}]+}.*"));
        assertFalse(config.matches("(?s).*\\$\\{DESIGNPM_TEST_DB_PASSWORD:[^}]+}.*"));
        assertFalse(config.contains("createDatabaseIfNotExist=true"));
    }
}
