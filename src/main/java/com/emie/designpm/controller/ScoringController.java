package com.emie.designpm.controller;

import com.emie.designpm.service.ProjectService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/scoring")
public class ScoringController {

    private final ProjectService projectService;

    public ScoringController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /**
     * 待评分任务列表（一次查询返回，替代前端 N+1 次 API 调用）
     */
    @GetMapping("/pending")
    public ResponseEntity<List<Map<String, Object>>> getPendingScores(
            @RequestParam String role,
            @RequestParam String userId) {
        return ResponseEntity.ok(projectService.getPendingScoringTasks(role, userId));
    }
}
