package com.emie.designpm.repository;

import com.emie.designpm.entity.SubTask;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SubTaskRepository extends JpaRepository<SubTask, Long> {

    List<SubTask> findByDesignerId(String designerId);

    List<SubTask> findByDesignerIdOrderByCreatedAtDesc(String designerId);

    List<SubTask> findByProjectIdOrderByCreatedAtAsc(Long projectId);

    List<SubTask> findByStatusAndDesignerId(String status, String designerId);

    /** 悲观锁：设计师接单时锁定子任务行 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM SubTask t WHERE t.id = ?1")
    Optional<SubTask> findByIdForUpdate(Long id);

    @Query("SELECT COUNT(t) FROM SubTask t WHERE t.designerId = ?1 AND t.status IN ('pending', 'accepted', 'rejected')")
    long countByDesignerIdAndStatusIn(String designerId);

    /** 批量统计项目子任务数及完成数（避免 JOIN FETCH 加载全部子任务） */
    @Query("SELECT t.project.id, COUNT(t), " +
           "SUM(CASE WHEN t.status IN ('completed','approved','sales_approved','admin_approved') THEN 1 ELSE 0 END) " +
           "FROM SubTask t WHERE t.project.id IN (?1) GROUP BY t.project.id")
    List<Object[]> countTasksByProjectIds(List<Long> projectIds);

    /** 批量统计各状态子任务数（仪表盘用，避免 N+1） */
    @Query("SELECT t.status, COUNT(t) FROM SubTask t WHERE t.project.id IN (?1) GROUP BY t.status")
    List<Object[]> countStatusByProjectIds(List<Long> projectIds);

    /** 统计待评分的子任务数（approved 状态且有未完成评分） */
    @Query("SELECT COUNT(DISTINCT t.id) FROM SubTask t JOIN ScoringRecord s ON s.subTask.id = t.id " +
           "WHERE t.project.id IN (?1) AND t.status = 'approved' AND (s.aesthetics IS NULL OR s.innovation IS NULL)")
    long countPendingScoresByProjectIds(List<Long> projectIds);

    /** 批量查询多个设计师/供应链的子任务（减少 N+1） */
    @Query("SELECT t FROM SubTask t WHERE t.designerId IN (?1)")
    List<SubTask> findByDesignerIds(List<String> designerIds);
}
