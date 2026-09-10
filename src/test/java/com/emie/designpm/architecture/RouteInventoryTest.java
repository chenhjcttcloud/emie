package com.emie.designpm.architecture;

import com.emie.designpm.auth.RedisSessionStore;
import com.emie.designpm.notification.service.NotificationBroadcastJobService;
import com.emie.designpm.notification.service.NotificationRetryService;
import com.emie.designpm.sync.service.SyncQueueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * HTTP 路由清单快照：把当前所有接口（method + path）冻结在
 * src/test/resources/route-inventory.txt。任何新增 / 删除 / 改路径的 PR 都会让这个
 * 测试失败，逼开发确认这是有意的接口变更（并更新快照）。
 *
 * 只锁"接口表面"（method + path），锁不住响应体结构——那要等 Map&lt;String,Object&gt;
 * 换成 typed DTO 之后才有意义。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:route-inventory;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.flyway.enabled=false",
        "management.health.redis.enabled=false",
        "spring.task.scheduling.enabled=false",
        "app.feishu.sync-worker-enabled=false",
        "app.db.pool.isolation.enabled=false"
})
@ActiveProfiles("test")
class RouteInventoryTest {

    private static final Path SNAPSHOT = Path.of("src/test/resources/route-inventory.txt");

    @MockitoBean RedisSessionStore redisSessionStore;
    @MockitoBean SyncQueueService syncQueueService;
    @MockitoBean NotificationRetryService notificationRetryService;
    @MockitoBean NotificationBroadcastJobService notificationBroadcastJobService;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void http_routes_match_the_committed_snapshot() throws Exception {
        TreeSet<String> current = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            String methods = info.getMethodsCondition().getMethods().stream()
                    .map(Enum::name).sorted().collect(Collectors.joining("|"));
            var patterns = info.getPathPatternsCondition() == null ? List.<String>of()
                    : info.getPathPatternsCondition().getPatternValues().stream().sorted().toList();
            for (String p : patterns) {
                current.add((methods.isEmpty() ? "ANY" : methods) + " " + p);
            }
        }

        String rendered = String.join("\n", current) + "\n";

        if (!Files.exists(SNAPSHOT)) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, rendered);
            fail("route-inventory.txt 不存在，已生成初始快照（" + current.size() + " 条路由）。请检查并提交。");
        }

        String expected = Files.readString(SNAPSHOT);
        if (!expected.equals(rendered)) {
            TreeSet<String> was = new TreeSet<>(List.of(expected.strip().split("\n")));
            var added = current.stream().filter(r -> !was.contains(r)).toList();
            var removed = was.stream().filter(r -> !current.contains(r)).toList();
            fail("HTTP 路由表和快照不一致——若是有意的接口变更，请更新 " + SNAPSHOT + "：\n"
                    + "  新增:\n    " + String.join("\n    ", added) + "\n"
                    + "  删除:\n    " + String.join("\n    ", removed));
        }
        assertEquals(expected, rendered);
    }
}
