package com.emie.designpm;

import com.emie.designpm.service.NotificationRetryService;
import com.emie.designpm.service.NotificationBroadcastJobService;
import com.emie.designpm.service.RedisSessionStore;
import com.emie.designpm.service.SyncQueueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:health-endpoint;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.flyway.enabled=false",
        "management.health.redis.enabled=false",
        "management.endpoint.health.show-components=always",
        "spring.task.scheduling.enabled=false",
        "app.feishu.sync-worker-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(HealthEndpointIntegrationTest.RedisHealthStub.class)
class HealthEndpointIntegrationTest {

    @Autowired MockMvc mvc;

    @MockitoBean RedisSessionStore redisSessionStore;
    @MockitoBean SyncQueueService syncQueueService;
    @MockitoBean NotificationRetryService notificationRetryService;
    @MockitoBean NotificationBroadcastJobService notificationBroadcastJobService;

    @Test
    void healthIsPublicAndIncludesDatabaseAndRedisComponents() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.redis.status").value("UP"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RedisHealthStub {
        @Bean
        HealthIndicator redisHealthIndicator() {
            return () -> Health.up().build();
        }
    }
}
