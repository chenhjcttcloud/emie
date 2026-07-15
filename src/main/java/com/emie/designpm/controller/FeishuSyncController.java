package com.emie.designpm.controller;

import com.emie.designpm.repository.ProjectRepository;
import com.emie.designpm.repository.ActivityLogRepository;
import com.emie.designpm.repository.ScoringRepository;
import com.emie.designpm.repository.SubTaskRepository;
import com.emie.designpm.service.FeishuBaseService;
import com.emie.designpm.service.SyncQueueService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 飞书同步管理接口
 */
@RestController
@RequestMapping("/api/admin/sync")
public class FeishuSyncController {

    private final SyncQueueService syncQueueService;
    private final FeishuBaseService feishuBaseService;
    private final ProjectRepository projectRepository;
    private final SubTaskRepository subTaskRepository;
    private final ScoringRepository scoringRepository;
    private final ActivityLogRepository activityLogRepository;

    public FeishuSyncController(SyncQueueService syncQueueService,
                                FeishuBaseService feishuBaseService,
                                ProjectRepository projectRepository,
                                SubTaskRepository subTaskRepository,
                                ScoringRepository scoringRepository,
                                ActivityLogRepository activityLogRepository) {
        this.syncQueueService = syncQueueService;
        this.feishuBaseService = feishuBaseService;
        this.projectRepository = projectRepository;
        this.subTaskRepository = subTaskRepository;
        this.scoringRepository = scoringRepository;
        this.activityLogRepository = activityLogRepository;
    }

    /** 同步状态统计 */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(syncQueueService.getStats());
    }

    /** 飞书 Base 配置 */
    @GetMapping("/config")
    public ResponseEntity<Map<String, String>> getConfig() {
        return ResponseEntity.ok(feishuBaseService.getConfig());
    }

    /**
     * 只读检查备份表是否属于当前 Base 且当前应用可访问。
     * 不创建、更新或重试任何飞书记录。
     */
    @PostMapping("/validate-backups")
    public ResponseEntity<Map<String, Object>> validateBackups() {
        try {
            Map<String, Object> result = feishuBaseService.validateBackupTables();
            return Boolean.TRUE.equals(result.get("valid"))
                    ? ResponseEntity.ok(result)
                    : ResponseEntity.unprocessableEntity().body(result);
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of(
                    "valid", false,
                    "message", "无法读取飞书数据表，请检查应用权限和网络后重试"));
        }
    }

    /** 全量重刷（重新入队所有数据） */
    @PostMapping("/full-resync")
    public ResponseEntity<Map<String, Object>> fullResync() {
        try {
            Map<String, Object> validation = feishuBaseService.validateBackupTables();
            if (!Boolean.TRUE.equals(validation.get("valid"))) {
                Map<String, Object> result = new LinkedHashMap<>(validation);
                result.put("message", "备份表预检失败，未执行全量重刷");
                return ResponseEntity.unprocessableEntity().body(result);
            }
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of(
                    "valid", false,
                    "message", "无法完成备份表预检，未执行全量重刷"));
        }
        List<Long> projectIds = projectRepository.findAll().stream().map(p -> p.getId()).toList();
        List<Long> taskIds = subTaskRepository.findAll().stream().map(t -> t.getId()).toList();
        List<Long> scoringIds = scoringRepository.findAll().stream().map(s -> s.getId()).toList();
        List<Long> logIds = activityLogRepository.findAll().stream().map(l -> l.getId()).toList();

        try {
            feishuBaseService.reconcileMirrors(
                    new java.util.HashSet<>(projectIds), new java.util.HashSet<>(taskIds),
                    new java.util.HashSet<>(scoringIds), new java.util.HashSet<>(logIds));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of(
                    "message", "主表镜像删除对账失败，未执行全量重刷"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project", syncQueueService.enqueueAll("project", projectIds));
        result.put("sub_task", syncQueueService.enqueueAll("sub_task", taskIds));
        result.put("scoring_record", syncQueueService.enqueueAll("scoring_record", scoringIds));
        result.put("activity_log", syncQueueService.enqueueAll("activity_log", logIds));
        result.put("message", "全量重刷已入队");
        return ResponseEntity.ok(result);
    }

    /** 初始化飞书 Base（机器人自动创建多维表格） */
    @PostMapping("/init")
    public ResponseEntity<Map<String, Object>> initBase() {
        try {
            Map<String, Object> result = feishuBaseService.initBase();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "飞书 Base 初始化失败，请检查配置后重试");
            return ResponseEntity.status(500).body(err);
        }
    }

    /** 为当前主表和备份表补齐两级审核同步字段。 */
    @PostMapping("/ensure-review-fields")
    public ResponseEntity<Map<String, Object>> ensureReviewFields() {
        try {
            return ResponseEntity.ok(feishuBaseService.ensureReviewWorkflowFields());
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of(
                    "error", "补充飞书审核字段失败，请检查表格权限及同名字段类型"));
        }
    }

    /** 为当前八张表补齐主表镜像与备份保留策略字段。 */
    @PostMapping("/ensure-mirror-fields")
    public ResponseEntity<Map<String, Object>> ensureMirrorFields() {
        try {
            return ResponseEntity.ok(feishuBaseService.ensureMirrorStrategyFields());
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of(
                    "error", "补充飞书镜像字段失败，请检查表格权限及同名字段类型"));
        }
    }
}
