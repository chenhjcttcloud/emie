package com.emie.designpm.service;

import com.emie.designpm.controller.AuthController;
import com.emie.designpm.entity.DesignRequirement;
import com.emie.designpm.entity.DesignRequirementScore;
import com.emie.designpm.repository.DesignRequirementScoreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class DesignRequirementScoringService {
    private final DesignRequirementScoreRepository scores;

    public DesignRequirementScoringService(DesignRequirementScoreRepository scores) {
        this.scores = scores;
    }

    @Transactional
    public void initialize(DesignRequirement d) {
        create(d, "self", "designer", d.getDesignerId(), d.getDesignerName(), "waiting");
        String creatorRole = normalizeRole(d.getResponsibleRole());
        if ("sales".equals(creatorRole) || "promotion".equals(creatorRole)) {
            create(d, "review", creatorRole, d.getResponsibleId(), d.getResponsibleName(), "waiting");
            create(d, "review", "planner", d.getPlannerId(), d.getPlannerName(), "waiting");
        } else if ("planner".equals(creatorRole)) {
            create(d, "review", "planner", d.getResponsibleId(), d.getResponsibleName(), "waiting");
            create(d, "review", "admin", null, "管理员", "waiting");
        }
    }

    @Transactional
    public void activateSelfScore(DesignRequirement d) {
        List<DesignRequirementScore> records = scores.findByRequirementIdOrderByIdAsc(d.getId());
        DesignRequirementScore self = records.stream()
                .filter(s -> "self".equals(s.getStage())).findFirst()
                .orElseThrow(() -> new IllegalStateException("该需求尚未配置设计师自评"));
        // 每次重新交付都视为一个全新的评分周期，绝不能沿用上次的任何分数。
        for (DesignRequirementScore record : records) {
            record.setStatus(record == self ? "pending" : "waiting");
            record.setScore(null);
            record.setScoredAt(null);
            if ("admin".equals(record.getRole())) {
                record.setReviewerId(null);
                record.setReviewerName("管理员");
            }
        }
        scores.saveAll(records);
    }

    @Transactional
    public void submitSelfScore(DesignRequirement d, AuthController.AuthSession session, int score) {
        if (!Objects.equals(d.getDesignerId(), session.userId()) || !"designer".equals(normalizeRole(session.role()))) {
            throw new IllegalArgumentException("仅该需求的设计师可以自评");
        }
        DesignRequirementScore self = ownPending(d, session, "self");
        complete(self, session, score);
        List<DesignRequirementScore> records = scores.findByRequirementIdOrderByIdAsc(d.getId());
        records.stream().filter(s -> "review".equals(s.getStage())).forEach(s -> {
            s.setStatus("pending");
            s.setScore(null);
            s.setScoredAt(null);
        });
        scores.saveAll(records);
        d.setStatus("pending_review");
    }

    @Transactional
    public void submitReview(DesignRequirement d, AuthController.AuthSession session, int score) {
        DesignRequirementScore record = ownPending(d, session, "review");
        complete(record, session, score);
        boolean allDone = scores.findByRequirementIdOrderByIdAsc(d.getId()).stream()
                .filter(s -> "review".equals(s.getStage()))
                .allMatch(s -> "completed".equals(s.getStatus()));
        d.setStatus(allDone ? "completed" : "pending_review");
    }

    public List<Map<String, Object>> pendingItems(String role, String userId) {
        return scores.findVisibleForReviewer(normalizeRole(role), userId).stream().map(s -> {
            DesignRequirement d = s.getRequirement();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("itemKind", "design_requirement");
            item.put("requirementId", d.getId());
            item.put("taskId", d.getId());
            item.put("taskName", d.getName());
            item.put("taskStatus", d.getStatus());
            item.put("projectId", d.getId());
            item.put("projectType", "design_requirement");
            item.put("projectName", d.getName());
            item.put("plannerId", d.getPlannerId());
            item.put("plannerName", d.getPlannerName());
            item.put("plannedDate", d.getDeadline());
            item.put("designerId", d.getDesignerId());
            item.put("designerName", d.getDesignerName());
            item.put("isPending", "pending".equals(s.getStatus()));
            item.put("scoringRecords", scoreMaps(scores.findByRequirementIdOrderByIdAsc(d.getId())));
            return item;
        }).toList();
    }

    public List<Map<String, Object>> scoreMaps(List<DesignRequirementScore> records) {
        return records.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", s.getRole());
            m.put("stage", s.getStage());
            m.put("reviewerId", s.getReviewerId());
            m.put("reviewerName", s.getReviewerName());
            m.put("status", s.getStatus());
            m.put("score", s.getScore());
            m.put("scoredAt", s.getScoredAt());
            return m;
        }).toList();
    }

    private DesignRequirementScore ownPending(DesignRequirement d, AuthController.AuthSession session, String stage) {
        String role = normalizeRole(session.role());
        return scores.findByRequirementIdOrderByIdAsc(d.getId()).stream()
                .filter(s -> stage.equals(s.getStage()) && "pending".equals(s.getStatus()))
                .filter(s -> Objects.equals(s.getReviewerId(), session.userId())
                        || (s.getReviewerId() == null && Objects.equals(s.getRole(), role)))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("当前没有需要您完成的评分"));
    }

    private void complete(DesignRequirementScore record, AuthController.AuthSession session, int value) {
        if (value < 1 || value > 100) throw new IllegalArgumentException("评分必须为1-100分");
        record.setScore(value);
        record.setStatus("completed");
        record.setReviewerId(session.userId());
        record.setReviewerName(session.name());
        record.setScoredAt(LocalDateTime.now());
        scores.save(record);
    }

    private void create(DesignRequirement d, String stage, String role, String id, String name, String status) {
        DesignRequirementScore score = new DesignRequirementScore();
        score.setRequirement(d);
        score.setStage(stage);
        score.setRole(role);
        score.setReviewerId(id);
        score.setReviewerName(name);
        score.setStatus(status);
        scores.save(score);
    }

    private String normalizeRole(String role) {
        if (role == null) return "";
        if ("Promotion".equalsIgnoreCase(role) || "product_promotion".equalsIgnoreCase(role)
                || "product-promotion".equalsIgnoreCase(role)) return "promotion";
        return role.toLowerCase(Locale.ROOT);
    }
}
