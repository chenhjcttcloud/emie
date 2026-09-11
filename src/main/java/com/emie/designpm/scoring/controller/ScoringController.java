package com.emie.designpm.scoring.controller;

import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.designrequirement.service.DesignRequirementScoringService;
import com.emie.designpm.project.service.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/scoring")
public class ScoringController {

    private final ProjectService projectService;
    private final DesignRequirementScoringService designRequirementScoringService;

    public ScoringController(
            ProjectService projectService, DesignRequirementScoringService designRequirementScoringService) {
        this.projectService = projectService;
        this.designRequirementScoringService = designRequirementScoringService;
    }

    /**
     * 待评分任务列表（一次查询返回，替代前端 N+1 次 API 调用）
     */
    @GetMapping("/pending")
    public ResponseEntity<List<Map<String, Object>>> getPendingScores(
            @RequestParam String role, @RequestParam String userId, HttpServletRequest request) {
        AuthSession session = (AuthSession) request.getAttribute("authSession");
        role = session.role();
        userId = session.userId();
        List<Map<String, Object>> result = new ArrayList<>(projectService.getPendingScoringTasks(role, userId));
        result.addAll(designRequirementScoringService.pendingItems(role, userId));
        return ResponseEntity.ok(result);
    }
}
