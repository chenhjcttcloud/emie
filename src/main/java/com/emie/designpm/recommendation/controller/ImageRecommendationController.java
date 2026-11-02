package com.emie.designpm.recommendation.controller;

import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.recommendation.service.ImageRecommendationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/subtasks/{taskId}/image-recommendations")
public class ImageRecommendationController {
    private final ImageRecommendationService service;

    public ImageRecommendationController(ImageRecommendationService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> recommendations(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestParam(defaultValue = "8") int limit,
            @RequestParam(defaultValue = "false") boolean refresh,
            HttpServletRequest request) {
        return service.recommendations(projectId, taskId, limit, refresh, session(request));
    }

    @PostMapping("/{libraryItemId}/associate")
    public Map<String, Object> associate(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable Long libraryItemId,
            HttpServletRequest request) {
        service.associate(projectId, taskId, libraryItemId, session(request));
        return service.recommendations(projectId, taskId, 8, false, session(request));
    }

    private static AuthSession session(HttpServletRequest request) {
        return (AuthSession) request.getAttribute("authSession");
    }
}
