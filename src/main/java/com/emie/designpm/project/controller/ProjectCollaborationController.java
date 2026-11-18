package com.emie.designpm.project.controller;

import com.emie.designpm.project.service.ProjectCollaborationService;
import com.emie.designpm.project.service.ProjectViewSupport;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectCollaborationController {
    private final ProjectCollaborationService collaborations;
    private final ProjectViewSupport view;
    private final ProjectRequestSupport requests;

    public ProjectCollaborationController(
            ProjectCollaborationService collaborations, ProjectViewSupport view, ProjectRequestSupport requests) {
        this.collaborations = collaborations;
        this.view = view;
        this.requests = requests;
    }

    @PutMapping("/{projectId}/collaborators")
    public ResponseEntity<?> update(
            @PathVariable Long projectId, @RequestBody CollaboratorsRequest body, HttpServletRequest request) {
        ResponseEntity<?> denied = requests.denyUnless(request, "subtask.create");
        if (denied != null) return denied;
        try {
            return ResponseEntity.ok(view.toDetail(
                    collaborations.replaceCollaborators(projectId, body.userIds(), requests.getSession(request))));
        } catch (SecurityException error) {
            return ResponseEntity.status(403).body(Map.of("error", error.getMessage()));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
        }
    }

    public record CollaboratorsRequest(List<String> userIds) {}
}
