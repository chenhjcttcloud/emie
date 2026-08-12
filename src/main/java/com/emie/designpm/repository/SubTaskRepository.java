package com.emie.designpm.repository;

import com.emie.designpm.entity.SubTask;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;

import java.util.List;
import java.util.Optional;

public interface SubTaskRepository extends JpaRepository<SubTask, Long> {

    interface IntegritySubTaskProjection {
        Long getId();
        String getReferenceImagesJson();
        String getAttachmentsJson();
    }

    interface DashboardTaskProjection {
        Long getId();
        Long getProjectId();
        String getStatus();
        String getWorkflowStage();
    }

    @Query("SELECT t.id AS id, t.project.id AS projectId, t.status AS status, " +
            "t.workflowStage AS workflowStage FROM SubTask t WHERE t.project.id IN :projectIds")
    List<DashboardTaskProjection> findDashboardTasksByProjectIds(@Param("projectIds") List<Long> projectIds);

    @Query("SELECT t.id AS id, t.referenceImagesJson AS referenceImagesJson, " +
            "t.attachmentsJson AS attachmentsJson FROM SubTask t " +
            "WHERE t.id > :afterId ORDER BY t.id ASC")
    List<IntegritySubTaskProjection> findIntegritySubTasksAfter(@Param("afterId") Long afterId, Pageable pageable);

    /** 后台飞书同步只需要子任务及所属项目基础字段，显式预加载项目关系。 */
    @Query("SELECT t FROM SubTask t JOIN FETCH t.project WHERE t.id = :id")
    Optional<SubTask> findByIdWithProject(@Param("id") Long id);

    @Query("SELECT t.project.id FROM SubTask t WHERE t.id = :id")
    Optional<Long> findProjectIdById(@Param("id") Long id);
    @Query("SELECT t.id FROM SubTask t WHERE t.id > :afterId ORDER BY t.id ASC")
    List<Long> findIdsAfter(@Param("afterId") Long afterId, Pageable pageable);

    @Query("SELECT t.id FROM SubTask t WHERE t.updatedAt > :after AND t.updatedAt <= :until ORDER BY t.updatedAt ASC, t.id ASC")
    List<Long> findIdsUpdatedBetween(@Param("after") LocalDateTime after, @Param("until") LocalDateTime until, Pageable pageable);

    List<SubTask> findByDesignerId(String designerId);

    List<SubTask> findByDesignerIdOrderByCreatedAtDesc(String designerId);

    List<SubTask> findByProjectIdOrderByCreatedAtAsc(Long projectId);

    @Query("SELECT t FROM SubTask t JOIN FETCH t.project p " +
           "WHERE t.allocationStatus = 'market_open' AND t.status = 'pending' " +
           "AND t.designerId IS NULL AND t.assigneeRole = 'designer' " +
           "AND p.status NOT IN ('terminated', 'paused', 'pending_terminate') " +
           "ORDER BY t.marketPublishedAt DESC, t.id DESC")
    List<SubTask> findOpenDesignerMarketTasks();

    List<SubTask> findByStatusAndDesignerId(String status, String designerId);

    List<SubTask> findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(String status, LocalDateTime cutoff);

    @Query("SELECT t FROM SubTask t JOIN FETCH t.project p " +
            "WHERE t.status = :status AND t.createdAt BETWEEN :from AND :to ORDER BY t.createdAt ASC")
    List<SubTask> findTop100ByStatusAndCreatedAtBetweenOrderByCreatedAtAsc(
            @Param("status") String status, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT DISTINCT t FROM SubTask t JOIN FETCH t.project p " +
            "WHERE t.designerId = :userId OR t.publisherId = :userId ORDER BY t.updatedAt DESC, t.id DESC")
    List<SubTask> findMySubTasks(@Param("userId") String userId);

    @Query("SELECT DISTINCT t FROM SubTask t JOIN FETCH t.project p " +
            "WHERE t.designerId IN :userIds OR t.publisherId IN :userIds ORDER BY t.updatedAt DESC, t.id DESC")
    List<SubTask> findDepartmentSubTasks(@Param("userIds") List<String> userIds);

    /** 悲观锁：设计师接单时锁定子任务行 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM SubTask t WHERE t.id = ?1")
    Optional<SubTask> findByIdForUpdate(Long id);

    @Query("SELECT COUNT(t) FROM SubTask t WHERE t.designerId = ?1 AND t.status IN ('pending', 'accepted', 'rejected')")
    long countByDesignerIdAndStatusIn(String designerId);

    /** 抢单额度仅统计仍在执行的 A/B 类主任务。 */
    @Query("SELECT COUNT(t) FROM SubTask t WHERE t.designerId = :designerId " +
           "AND t.status IN ('accepted', 'delivered', 'submitted_for_review', 'planner_approved', 'sales_approved', 'admin_approved', 'rejected') " +
           "AND UPPER(COALESCE(t.pointRuleCode, '')) LIKE CONCAT(UPPER(:categoryPrefix), '%')")
    long countActiveMainTasksByCategory(@Param("designerId") String designerId,
                                        @Param("categoryPrefix") String categoryPrefix);

    /** 左侧“我的子任务”徽章：按当前执行角色隔离，兼容历史未填写角色的设计任务。 */
    @Query("SELECT COUNT(t) FROM SubTask t WHERE t.designerId = ?1 AND t.status IN ('pending', 'accepted', 'rejected') " +
           "AND (t.assigneeRole = ?2 OR (?2 = 'designer' AND (t.assigneeRole IS NULL OR t.assigneeRole = '')))")
    long countByDesignerIdAndRoleAndActionableStatus(String designerId, String assigneeRole);

    /** 批量统计项目子任务数及完成数（避免 JOIN FETCH 加载全部子任务） */
    @Query("SELECT t.project.id, COUNT(t), " +
           "SUM(CASE WHEN t.status IN ('delivered','planner_approved','sales_approved','admin_approved','completed') THEN 1 ELSE 0 END) " +
           ", SUM(CASE WHEN t.status IN ('pending','accepted','rejected') THEN 1 ELSE 0 END) " +
           "FROM SubTask t WHERE t.project.id IN (?1) GROUP BY t.project.id")
    List<Object[]> countTasksByProjectIds(List<Long> projectIds);

    /** 批量统计各状态子任务数（仪表盘用，避免 N+1） */
    @Query("SELECT t.status, COUNT(t) FROM SubTask t WHERE t.project.id IN (?1) GROUP BY t.status")
    List<Object[]> countStatusByProjectIds(List<Long> projectIds);

    /** 批量查询多个设计师/供应链的子任务（减少 N+1） */
    @Query("SELECT t FROM SubTask t WHERE t.designerId IN (?1)")
    List<SubTask> findByDesignerIds(List<String> designerIds);

    /** 直接从数据库检查可见项目的全部子任务文件引用，避免受 fetch join 过滤集合和一级缓存影响。 */
    @Query("SELECT COUNT(t) FROM SubTask t WHERE t.project.id IN (?1) " +
           "AND (t.referenceImagesJson LIKE CONCAT('%', ?2, '%') " +
           "OR t.attachmentsJson LIKE CONCAT('%', ?2, '%'))")
    long countFileReferencesByProjectIds(List<Long> projectIds, String storedName);
}
