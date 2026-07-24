package com.emie.designpm.controller;

import com.emie.designpm.service.ProjectService;
import com.emie.designpm.service.DesignRequirementScoringService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/scoring")
public class ScoringController {

    private final ProjectService projectService;
    private final DesignRequirementScoringService designRequirementScoringService;

    public ScoringController(ProjectService projectService,
                             DesignRequirementScoringService designRequirementScoringService) {
        this.projectService = projectService;
        this.designRequirementScoringService = designRequirementScoringService;
    }

    /**
     * 待评分任务列表（一次查询返回，替代前端 N+1 次 API 调用）
     */
    @GetMapping("/pending")
    public ResponseEntity<List<Map<String, Object>>> getPendingScores(
            @RequestParam String role,
            @RequestParam String userId,
            HttpServletRequest request) {
        AuthController.AuthSession session = (AuthController.AuthSession) request.getAttribute("authSession");
        role = session.role();
        userId = session.userId();
        List<Map<String, Object>> result = new ArrayList<>(projectService.getPendingScoringTasks(role, userId));
        result.addAll(designRequirementScoringService.pendingItems(role, userId));
        return ResponseEntity.ok(result);
    }
}
