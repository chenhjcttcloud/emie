package com.emie.designpm.service;

import com.emie.designpm.entity.*;
import com.emie.designpm.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 飞书同步工作线程
 * 定时消费 sync_queue 表，将数据写入飞书多维表格
 */
@Component
public class SyncWorker {

    private static final Logger log = LoggerFactory.getLogger(SyncWorker.class);

    private final SyncQueueRepository syncQueueRepository;
    private final ProjectRepository projectRepository;
    private final SubTaskRepository subTaskRepository;
    private final ScoringRepository scoringRepository;
    private final FeishuBaseService feishuBaseService;
    private final SyncQueueService syncQueueService;

    public SyncWorker(SyncQueueRepository syncQueueRepository,
                      ProjectRepository projectRepository,
                      SubTaskRepository subTaskRepository,
                      ScoringRepository scoringRepository,
                      FeishuBaseService feishuBaseService,
                      SyncQueueService syncQueueService) {
        this.syncQueueRepository = syncQueueRepository;
        this.projectRepository = projectRepository;
        this.subTaskRepository = subTaskRepository;
        this.scoringRepository = scoringRepository;
        this.feishuBaseService = feishuBaseService;
        this.syncQueueService = syncQueueService;
    }

    /** 每 30 秒消费队列 */
    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void processQueue() {
        List<SyncQueue> items = syncQueueRepository.findTop20ByStatusOrderByCreatedAtAsc("pending");
        if (items.isEmpty()) return;

        log.info("飞书同步队列: {} 条待处理", items.size());

        for (SyncQueue item : items) {
            try {
                item.setStatus("processing");
                syncQueueRepository.save(item);

                switch (item.getEntityType()) {
                    case "project" -> {
                        if ("delete".equals(item.getAction())) {
                            deleteProject(item.getEntityId());
                        } else {
                            syncProject(item.getEntityId());
                        }
                    }
                    case "sub_task" -> {
                        if ("delete".equals(item.getAction())) {
                            deleteSubTask(item.getEntityId());
                        } else {
                            syncSubTask(item.getEntityId());
                        }
                    }
                    case "scoring_record" -> {
                        if ("delete".equals(item.getAction())) {
                            deleteScoring(item.getEntityId());
                        } else {
                            syncScoring(item.getEntityId());
                        }
                    }
                    default -> log.warn("未知同步类型: {}", item.getEntityType());
                }

                item.setStatus("done");
                item.setErrorMsg(null);
                log.debug("同步成功: {} {}", item.getEntityType(), item.getEntityId());

            } catch (Exception e) {
                item.setRetryCount(item.getRetryCount() + 1);
                item.setErrorMsg(e.getMessage() != null ? e.getMessage().substring(0, Math.min(500, e.getMessage().length())) : "未知错误");
                if (item.getRetryCount() >= 3) {
                    item.setStatus("fail");
                    log.warn("同步失败(已重试3次): {} {} - {}", item.getEntityType(), item.getEntityId(), e.getMessage());
                } else {
                    item.setStatus("pending");
                    log.warn("同步失败(将重试 {}/3): {} {} - {}", item.getRetryCount(), item.getEntityType(), item.getEntityId(), e.getMessage());
                }
            } finally {
                syncQueueRepository.save(item);
            }
        }
    }

    /**
     * 周期性全量对账，用数据库当前记录补回飞书端被直接删除的数据。
     * 与 30 秒队列消费解耦，并仅在队列空闲时执行，避免持续全量写入和新任务饥饿。
     */
    @Scheduled(
            fixedDelayString = "${app.feishu.reconcile-delay-ms:3600000}",
            initialDelayString = "${app.feishu.reconcile-initial-delay-ms:300000}"
    )
    @Transactional
    public void reconcileCurrentData() {
        if (syncQueueRepository.countByStatus("pending") > 0
                || syncQueueRepository.countByStatus("processing") > 0) {
            log.debug("飞书全量对账跳过：同步队列仍有待处理任务");
            return;
        }
        syncQueueService.enqueueAllForReconciliation("project",
                projectRepository.findAll().stream().map(Project::getId).toList());
        syncQueueService.enqueueAllForReconciliation("sub_task",
                subTaskRepository.findAll().stream().map(SubTask::getId).toList());
        syncQueueService.enqueueAllForReconciliation("scoring_record",
                scoringRepository.findAll().stream().map(ScoringRecord::getId).toList());
    }

    private void syncProject(Long projectId) throws Exception {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new Exception("项目不存在: " + projectId));

        int taskCount = p.getTasks() != null ? p.getTasks().size() : 0;
        int doneCount = p.getTasks() != null ?
                (int) p.getTasks().stream()
                        .filter(t -> List.of("approved", "completed", "sales_approved", "admin_approved").contains(t.getStatus()))
                        .count() : 0;
        int progress = taskCount > 0 ? (doneCount * 100 / taskCount) : 0;

        String categoryName = p.getProductCategory() != null ? p.getProductCategory().getName() : null;

        feishuBaseService.syncProject(
                p.getId(), p.getType(), p.getStatus(),
                p.getSalesName(), p.getPlannerName(),
                p.getDeadline(), categoryName,
                p.getPriceRange(), taskCount, progress,
                p.getCreatedAt()
        );
    }

    private void syncSubTask(Long taskId) throws Exception {
        SubTask t = subTaskRepository.findById(taskId)
                .orElseThrow(() -> new Exception("子任务不存在: " + taskId));

        feishuBaseService.syncSubTask(
                t.getId(), t.getName(), t.getStatus(),
                t.getDesignerName(), t.getPlannedDate(),
                t.getActualDate(), t.getSelfScore(),
                t.getProject() != null ? t.getProject().getId() : null,
                t.getCreatedAt()
        );
    }

    private void syncScoring(Long recordId) throws Exception {
        ScoringRecord r = scoringRepository.findById(recordId)
                .orElseThrow(() -> new Exception("评分记录不存在: " + recordId));

        feishuBaseService.syncScoring(
                r.getId(), r.getRole(), r.getScore(),
                r.getWeight(),
                r.getSubTask() != null ? r.getSubTask().getId() : null
        );
    }

    private void deleteProject(Long projectId) throws Exception {
        // 级联删除关联的子任务和评分记录
        List<SubTask> tasks = subTaskRepository.findByProjectIdOrderByCreatedAtAsc(projectId);
        for (SubTask t : tasks) {
            List<ScoringRecord> scores = scoringRepository.findBySubTaskId(t.getId());
            for (ScoringRecord s : scores) {
                feishuBaseService.deleteScoringRecord(s.getId());
            }
            feishuBaseService.deleteSubTaskRecord(t.getId());
        }
        // 删除项目本身
        feishuBaseService.deleteProjectRecord(projectId);
        log.info("已删除飞书项目及关联数据: project={}, subTasks={}, scores={}",
                projectId, tasks.size(), tasks.stream().flatMap(t -> scoringRepository.findBySubTaskId(t.getId()).stream()).count());
    }

    private void deleteSubTask(Long taskId) throws Exception {
        feishuBaseService.deleteSubTaskRecord(taskId);
        log.info("已删除飞书子任务记录: {}", taskId);
    }

    private void deleteScoring(Long recordId) throws Exception {
        feishuBaseService.deleteScoringRecord(recordId);
        log.info("已删除飞书评分记录: {}", recordId);
    }
}
