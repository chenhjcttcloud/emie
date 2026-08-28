package com.emie.designpm.service;

import com.emie.designpm.entity.User;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubTaskInputPolicyTest {
    private final UserService users = mock(UserService.class);
    private final SubTaskInputPolicy policy = new SubTaskInputPolicy(users);

    @Test void normalizesAndDeduplicatesSkillTags() {
        assertEquals("[\"ID\",\"视觉\"]", policy.skillTags(List.of(" ID ", "视觉", "ID")));
    }

    @Test void rejectsInvalidMilestoneMonth() {
        assertThrows(RuntimeException.class, () -> policy.milestoneMonth("2026-13"));
    }

    @Test void validatesCollaboratorAndNormalizesOutput() {
        User user = new User(); user.setRole("designer"); user.setName("协作者");
        when(users.getUserByUserId("d-2")).thenReturn(user);
        String result = policy.collaboratorAllocations(List.of(Map.of("userId", "d-2", "ratio", 20)), "d-1");
        assertTrue(result.contains("\"userId\":\"d-2\""));
        assertTrue(result.contains("\"name\":\"协作者\""));
        assertTrue(result.contains("\"ratio\":20"));
    }
}
