package com.emie.designpm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationTestServiceTest {

    @Test
    void testCardUsesFeishuCardJsonV2BodyObject() throws Exception {
        JsonNode card = new ObjectMapper().readTree(NotificationTestService.buildCard());

        assertEquals("2.0", card.path("schema").asText());
        assertTrue(card.path("body").isObject(), "JSON 2.0 的 body 必须是对象");
        assertTrue(card.path("body").path("elements").isArray(), "body 中必须提供 elements 数组");
        assertEquals("markdown", card.path("body").path("elements").get(0).path("tag").asText());
    }
}
