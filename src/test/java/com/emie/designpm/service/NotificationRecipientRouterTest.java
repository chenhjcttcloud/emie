package com.emie.designpm.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationRecipientRouterTest {

    @Test
    void devProfileRoutesEveryNotificationToTheConfiguredTesterAndDeduplicatesRoleBroadcasts() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        NotificationRecipientRouter router = new NotificationRecipientRouter(environment, "tester_01");

        assertTrue(router.isTestOverrideEnabled());
        assertEquals("tester_01", router.route("planner_01"));
        assertEquals(List.of("tester_01"), router.routeAll(List.of("planner_01", "planner_02")));
    }

    @Test
    void prodProfileNeverOverridesTheBusinessRecipient() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        NotificationRecipientRouter router = new NotificationRecipientRouter(environment, "tester_01");

        assertFalse(router.isTestOverrideEnabled());
        assertEquals("planner_01", router.route("planner_01"));
        assertEquals(List.of("planner_01", "planner_02"), router.routeAll(List.of("planner_01", "planner_02")));
    }

    @Test
    void prodProfileWinsEvenIfDevWasAccidentallyEnabledToo() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod", "dev");
        NotificationRecipientRouter router = new NotificationRecipientRouter(environment, "tester_01");

        assertFalse(router.isTestOverrideEnabled());
        assertEquals("planner_01", router.route("planner_01"));
    }
}
