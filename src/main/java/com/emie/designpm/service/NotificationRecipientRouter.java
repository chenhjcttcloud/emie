package com.emie.designpm.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 本地/测试环境的通知收件人覆盖。
 * 仅 dev、local profile 且显式指定测试账号时生效；生产环境始终保留业务收件人。
 */
@Component
public class NotificationRecipientRouter {
    private final Environment environment;
    private final String testRecipientUserId;

    public NotificationRecipientRouter(Environment environment,
                                       @Value("${app.notification.test-recipient-user-id:}") String testRecipientUserId) {
        this.environment = environment;
        this.testRecipientUserId = testRecipientUserId == null ? "" : testRecipientUserId.trim();
    }

    public boolean isTestOverrideEnabled() {
        return !testRecipientUserId.isBlank()
                && !environment.matchesProfiles("prod")
                && (environment.matchesProfiles("dev") || environment.matchesProfiles("local"));
    }

    public String route(String intendedRecipientUserId) {
        return isTestOverrideEnabled() ? testRecipientUserId : intendedRecipientUserId;
    }

    /** 角色群发在测试环境只保留一次，避免多个原收件人产生重复测试推送。 */
    public List<String> routeAll(Collection<String> intendedRecipientUserIds) {
        if (isTestOverrideEnabled()) return List.of(testRecipientUserId);
        return intendedRecipientUserIds == null ? List.of() : intendedRecipientUserIds.stream()
                .filter(Objects::nonNull).map(String::trim).filter(id -> !id.isBlank()).distinct().toList();
    }
}
