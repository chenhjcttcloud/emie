package com.emie.designpm.service;

import com.emie.designpm.controller.AuthController;
import com.emie.designpm.entity.DesignRequirement;
import com.emie.designpm.entity.DesignRequirementScore;
import com.emie.designpm.repository.DesignRequirementScoreRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DesignRequirementScoringServiceTest {

    @Test
    void salesFlowRequiresDesignerSelfScoreBeforeSalesAndPlannerAndCompletesAfterBoth() {
        Fixture f = fixture("sales", "sales-1", "销售一");

        assertRoles(f.records, "designer", "sales", "planner");
        assertTrue(f.records.stream().allMatch(s -> "waiting".equals(s.getStatus())));

        f.service.activateSelfScore(f.requirement);
        assertEquals("pending", record(f.records, "designer").getStatus());
        assertEquals("waiting", record(f.records, "sales").getStatus());

        f.service.submitSelfScore(f.requirement, session("designer-1", "designer", "设计师一"), 88);
        assertEquals("completed", record(f.records, "designer").getStatus());
        assertEquals("pending", record(f.records, "sales").getStatus());
        assertEquals("pending", record(f.records, "planner").getStatus());

        f.service.submitReview(f.requirement, session("sales-1", "sales", "销售一"), 90);
        assertEquals("pending_review", f.requirement.getStatus());
        f.service.submitReview(f.requirement, session("planner-1", "planner", "企划一"), 92);
        assertEquals("completed", f.requirement.getStatus());
    }

    @Test
    void promotionAndPlannerCreatorsReceiveTheConfiguredReviewerPairs() {
        Fixture promotion = fixture("Promotion", "promotion-1", "推广一");
        assertRoles(promotion.records, "designer", "promotion", "planner");

        Fixture planner = fixture("planner", "planner-1", "企划一");
        assertRoles(planner.records, "designer", "planner", "admin");
        DesignRequirementScore admin = record(planner.records, "admin");
        assertNull(admin.getReviewerId(), "管理员评分应进入管理员公共待办池");
    }

    @Test
    void rejectsEarlyUnauthorizedAndRepeatedScoresAndNewCycleClearsOldScores() {
        Fixture f = fixture("sales", "sales-1", "销售一");
        assertThrows(IllegalArgumentException.class,
                () -> f.service.submitSelfScore(f.requirement, session("sales-1", "sales", "销售一"), 80));

        f.service.activateSelfScore(f.requirement);
        f.service.submitSelfScore(f.requirement, session("designer-1", "designer", "设计师一"), 80);
        f.service.submitReview(f.requirement, session("sales-1", "sales", "销售一"), 81);
        assertThrows(IllegalArgumentException.class,
                () -> f.service.submitReview(f.requirement, session("sales-1", "sales", "销售一"), 82));

        f.service.activateSelfScore(f.requirement);
        assertEquals("pending", record(f.records, "designer").getStatus());
        for (DesignRequirementScore score : f.records) {
            assertNull(score.getScore());
            assertNull(score.getScoredAt());
        }
        assertEquals("waiting", record(f.records, "sales").getStatus());
        assertEquals("waiting", record(f.records, "planner").getStatus());
    }

    private Fixture fixture(String creatorRole, String creatorId, String creatorName) {
        DesignRequirementScoreRepository repository = mock(DesignRequirementScoreRepository.class);
        List<DesignRequirementScore> records = new ArrayList<>();
        when(repository.save(any())).thenAnswer(invocation -> {
            DesignRequirementScore score = invocation.getArgument(0);
            if (!records.contains(score)) records.add(score);
            return score;
        });

        DesignRequirement requirement = new DesignRequirement();
        requirement.setId(1L);
        requirement.setName("包装送审");
        requirement.setDesignerId("designer-1");
        requirement.setDesignerName("设计师一");
        requirement.setPlannerId("planner-1");
        requirement.setPlannerName("企划一");
        requirement.setResponsibleRole(creatorRole);
        requirement.setResponsibleId(creatorId);
        requirement.setResponsibleName(creatorName);

        when(repository.findByRequirementIdOrderByIdAsc(1L)).thenAnswer(ignored -> records);
        DesignRequirementScoringService service = new DesignRequirementScoringService(repository);
        service.initialize(requirement);
        return new Fixture(service, requirement, records);
    }

    private AuthController.AuthSession session(String id, String role, String name) {
        return new AuthController.AuthSession(id, role, name);
    }

    private DesignRequirementScore record(List<DesignRequirementScore> records, String role) {
        return records.stream().filter(s -> role.equals(s.getRole())).findFirst().orElseThrow();
    }

    private void assertRoles(List<DesignRequirementScore> records, String... roles) {
        assertEquals(List.of(roles), records.stream().map(DesignRequirementScore::getRole).toList());
    }

    private record Fixture(DesignRequirementScoringService service, DesignRequirement requirement,
                           List<DesignRequirementScore> records) {}
}
