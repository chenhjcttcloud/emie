package com.emie.designpm.service;

import com.emie.designpm.entity.Project;
import java.util.List;
import java.util.Map;

/** 子任务写操作边界；具体实现可从 ProjectService 独立迁移。 */
public interface SubTaskCommandService {
    Project addSubTask(Long projectId, Map<String, Object> body);
    Project updateSubTask(Long projectId, Long taskId, Map<String, Object> body);
    Project taskAccept(Long projectId, Long taskId, Map<String, Object> body);
    Project withdrawMarketTask(Long projectId, Long taskId, Map<String, Object> body);
    Project withdrawAcceptedTask(Long projectId, Long taskId, Map<String, Object> body);
    Project cancelAcceptedTask(Long projectId, Long taskId, Map<String, Object> body);
    Project deleteSubTask(Long projectId, Long taskId);
    Project taskDeliver(Long projectId, Long taskId, Map<String, Object> body);
    Project taskSubmitReview(Long projectId, Long taskId, Map<String, Object> body);
    Project taskRedeliver(Long projectId, Long taskId, Map<String, Object> body);
    Project taskConfirmRevision(Long projectId, Long taskId, Map<String, Object> body);
    Project taskCorrectDelivery(Long projectId, Long taskId, Map<String, Object> body);
    Project taskApprove(Long projectId, Long taskId, Map<String, Object> body);
    Project taskReject(Long projectId, Long taskId, Map<String, Object> body);
    Project submitScoring(Long projectId, Long taskId, Map<String, Object> body);
    List<Map<String, Object>> getDeliveryVersions(Long taskId);
    double currentScoringWeight(String projectType, String role);
}
