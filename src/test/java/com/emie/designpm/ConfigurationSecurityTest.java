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
        assertTrue(config.contains("ddl-auto: ${SPRING_JPA_DDL_AUTO:validate}"),
                "JPA schema mode must default to validate and only be explicitly overridden");
        assertFalse(config.matches("(?s).*\\$\\{DESIGNPM_DB_PASSWORD:[^}]+}.*"));
        assertFalse(config.matches("(?s).*\\$\\{DESIGNPM_TEST_DB_PASSWORD:[^}]+}.*"));
        assertFalse(config.contains("createDatabaseIfNotExist=true"));
        assertFalse(config.contains("open-in-view: true"));
        assertTrue(config.split("open-in-view: false", -1).length - 1 == 4,
                "所有运行 profile 都必须显式关闭 OSIV");
        assertTrue(config.contains("repositories:\n        enabled: false"),
                "未使用 Redis Repository 时应关闭自动扫描，避免将 JPA 仓储误判为 Redis 仓储");
    }
}
