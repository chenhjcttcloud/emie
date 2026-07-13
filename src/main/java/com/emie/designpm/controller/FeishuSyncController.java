package com.emie.designpm.controller;

import com.emie.designpm.repository.ProjectRepository;
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

    public FeishuSyncController(SyncQueueService syncQueueService,
                                FeishuBaseService feishuBaseService,
                                ProjectRepository projectRepository,
                                SubTaskRepository subTaskRepository,
                                ScoringRepository scoringRepository) {
        this.syncQueueService = syncQueueService;
        this.feishuBaseService = feishuBaseService;
        this.projectRepository = projectRepository;
        this.subTaskRepository = subTaskRepository;
        this.scoringRepository = scoringRepository;
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

    /** 全量重刷（重新入队所有数据） */
    @PostMapping("/full-resync")
    public ResponseEntity<Map<String, Object>> fullResync() {
        List<Long> projectIds = projectRepository.findAll().stream().map(p -> p.getId()).toList();
        List<Long> taskIds = subTaskRepository.findAll().stream().map(t -> t.getId()).toList();
        List<Long> scoringIds = scoringRepository.findAll().stream().map(s -> s.getId()).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project", syncQueueService.enqueueAll("project", projectIds));
        result.put("sub_task", syncQueueService.enqueueAll("sub_task", taskIds));
        result.put("scoring_record", syncQueueService.enqueueAll("scoring_record", scoringIds));
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
}
