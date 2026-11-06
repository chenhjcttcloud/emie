package com.emie.designpm.admin.repository;

import com.emie.designpm.entity.ActivityLog;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {

    List<ActivityLog> findTop200ByProjectIdOrderByTimeDesc(Long projectId);

    @Query(
            "SELECT l FROM ActivityLog l LEFT JOIN FETCH l.project p WHERE l.projectRefId = :projectId OR p.id = :projectId ORDER BY l.time DESC")
    List<ActivityLog> findProjectHistory(@Param("projectId") Long projectId, Pageable pageable);

    default List<ActivityLog> findProjectHistory(Long projectId) {
        return findProjectHistory(projectId, PageRequest.of(0, 200));
    }

    /** 完整读取交付与给分的相关审计事件，不受项目动态 200 条上限影响。 */
    @Query("SELECT l FROM ActivityLog l WHERE l.entityType = 'sub_task' AND l.entityId IN :taskIds "
            + "AND (l.action LIKE '发起子任务修改：%' OR l.action LIKE '产品企划确认成果：%') "
            + "ORDER BY l.time ASC, l.id ASC")
    List<ActivityLog> findSubTaskDeliveryHistory(@Param("taskIds") Collection<Long> taskIds);

    /** Durable cycle records may still need their original audit snapshots; include pre-cycle project logs. */
    @Query("SELECT l FROM ActivityLog l LEFT JOIN l.project p WHERE l.action LIKE '子任务驳回：%' "
            + "AND ((l.entityType = 'sub_task' AND l.entityId IN :taskIds) "
            + "OR ((l.entityType IS NULL OR l.entityType <> 'sub_task') "
            + "AND (l.projectRefId IN :projectIds OR p.id IN :projectIds))) ORDER BY l.time ASC, l.id ASC")
    List<ActivityLog> findSubTaskRejectionHistory(
            @Param("taskIds") Collection<Long> taskIds, @Param("projectIds") Collection<Long> projectIds);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            "UPDATE ActivityLog l SET l.project = null, l.projectRefId = coalesce(l.projectRefId, :projectId) WHERE l.project.id = :projectId")
    int detachProject(@Param("projectId") Long projectId);

    @Query("SELECT l.id FROM ActivityLog l WHERE l.id > :afterId ORDER BY l.id ASC")
    List<Long> findIdsAfter(@Param("afterId") Long afterId, Pageable pageable);

    @Query("SELECT l.id FROM ActivityLog l WHERE l.time > ?1 AND l.time <= ?2 ORDER BY l.time ASC, l.id ASC")
    List<Long> findIdsUpdatedBetween(LocalDateTime after, LocalDateTime until, Pageable pageable);

    @Query("SELECT l FROM ActivityLog l LEFT JOIN FETCH l.project p ORDER BY l.time DESC")
    List<ActivityLog> findAllWithProject();

    @Query(
            "SELECT l FROM ActivityLog l LEFT JOIN FETCH l.project p WHERE l.time >= ?1 AND l.time <= ?2 ORDER BY l.time DESC")
    List<ActivityLog> findByTimeBetween(LocalDateTime start, LocalDateTime end);
}
