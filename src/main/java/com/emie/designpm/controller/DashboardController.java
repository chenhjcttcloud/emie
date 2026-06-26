package com.emie.designpm.controller;

import com.emie.designpm.entity.Project;
import com.emie.designpm.repository.ProjectRepository;
import com.emie.designpm.repository.ScoringRepository;
import com.emie.designpm.service.ProjectService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final ProjectRepository projectRepository;
    private final ScoringRepository scoringRepository;
    private final ProjectService projectService;

    public DashboardController(ProjectRepository projectRepository,
                               ScoringRepository scoringRepository,
                               ProjectService projectService) {
        this.projectRepository = projectRepository;
        this.scoringRepository = scoringRepository;
        this.projectService = projectService;
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String userId) {

        List<Project> projects;
        if (role != null && userId != null) {
            projects = projectService.getProjectsByRoleAndUser(role, userId);
        } else {
            projects = projectRepository.findAll();
        }

        long totalProjects = projects.size();
        long channelProjects = projects.stream().filter(p -> "channel_custom".equals(p.getType())).count();
        long regularProjects = projects.stream().filter(p -> "regular".equals(p.getType())).count();
        long inProgress = projects.stream()
                .filter(p -> "in_progress".equals(projectService.computeProjectStatus(p)) || "completed_pending_score".equals(projectService.computeProjectStatus(p))).count();

        long allTasks = projects.stream().mapToLong(p -> p.getTasks().size()).sum();
        long approvedTasks = projects.stream()
                .flatMap(p -> p.getTasks().stream())
                .filter(t -> "approved".equals(t.getStatus()))
                .count();
        long pendingTasks = projects.stream()
                .flatMap(p -> p.getTasks().stream())
                .filter(t -> "pending".equals(t.getStatus()) || "delivered".equals(t.getStatus()))
                .count();

        long pendingScore = projects.stream()
                .flatMap(p -> p.getTasks().stream())
                .filter(t -> "approved".equals(t.getStatus()))
                .flatMap(t -> scoringRepository.findBySubTaskId(t.getId()).stream())
                .filter(sr -> sr.getAesthetics() == null || sr.getInnovation() == null)
                .count();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalProjects", totalProjects);
        stats.put("channelProjects", channelProjects);
        stats.put("regularProjects", regularProjects);
        stats.put("inProgress", inProgress);
        stats.put("allTasks", allTasks);
        stats.put("approvedTasks", approvedTasks);
        stats.put("pendingTasks", pendingTasks);
        stats.put("pendingScore", pendingScore);

        return ResponseEntity.ok(stats);
    }
}
