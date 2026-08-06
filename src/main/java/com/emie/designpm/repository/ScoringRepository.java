package com.emie.designpm.repository;

import com.emie.designpm.entity.ScoringRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

public interface ScoringRepository extends JpaRepository<ScoringRecord, Long> {

    /** 后台同步需要跨越评分、子任务、项目关系，显式一次性加载。 */
    @Query("SELECT s FROM ScoringRecord s JOIN FETCH s.subTask t JOIN FETCH t.project WHERE s.id = :id")
    Optional<ScoringRecord> findByIdWithTaskAndProject(@Param("id") Long id);

    @Query("SELECT s.id FROM ScoringRecord s WHERE s.id > :afterId ORDER BY s.id ASC")
    List<Long> findIdsAfter(@Param("afterId") Long afterId, Pageable pageable);

    @Query("SELECT s.id FROM ScoringRecord s WHERE s.updatedAt > :after AND s.updatedAt <= :until ORDER BY s.updatedAt ASC, s.id ASC")
    List<Long> findIdsUpdatedBetween(@Param("after") LocalDateTime after, @Param("until") LocalDateTime until, Pageable pageable);

    List<ScoringRecord> findBySubTaskId(Long subTaskId);

    Optional<ScoringRecord> findBySubTaskIdAndRole(Long subTaskId, String role);

    /** 批量查询多个子任务的评分记录，减少 N+1 查询 */
    @Query("SELECT s FROM ScoringRecord s WHERE s.subTask.id IN ?1")
    List<ScoringRecord> findBySubTaskIds(List<Long> subTaskIds);

    /** 批量查询多个项目的所有评分记录（通过 JOIN） */
    @Query("SELECT s FROM ScoringRecord s JOIN FETCH s.subTask t JOIN t.project p WHERE p.id IN ?1")
    List<ScoringRecord> findByProjectIds(List<Long> projectIds);

    void deleteBySubTaskId(Long subTaskId);

    @Modifying
    @Query("DELETE FROM ScoringRecord s WHERE s.subTask.id IN (SELECT t.id FROM SubTask t WHERE t.project.id = ?1)")
    void deleteByProjectId(Long projectId);

    @Query("SELECT COUNT(s) FROM ScoringRecord s WHERE s.role = ?1 AND s.reviewStatus = 'pending'")
    long countPendingByRole(String role);

    @Query("SELECT COUNT(s) FROM ScoringRecord s JOIN s.subTask t JOIN t.project p " +
           "WHERE p.type = 'channel_custom' AND s.role IN ('sales', 'planner') " +
           "AND s.reviewStatus = 'pending'")
    long countPendingChannelScore();

    @Query("SELECT COUNT(s) FROM ScoringRecord s WHERE s.reviewStatus = 'pending'")
    long countAllPendingScores();

    /** 待评分徽章：只返回数量，不加载项目、子任务和评分明细。 */
    @Query("SELECT COUNT(s) FROM ScoringRecord s JOIN s.subTask t JOIN t.project p " +
           "WHERE s.role = :role AND (s.reviewStatus = 'pending' OR " +
           "(s.reviewStatus IS NULL AND s.score IS NULL AND (s.aesthetics IS NULL OR s.innovation IS NULL))) " +
           "AND ((:role = 'planner' AND t.status = 'delivered') " +
           "OR (:role = 'admin' AND p.type <> 'channel_custom' AND t.status = 'planner_approved') " +
           "OR (:role = 'sales' AND p.type = 'channel_custom' AND t.status = 'planner_approved'))")
    long countPendingForRole(@Param("role") String role);

    @Query("SELECT COUNT(s) FROM ScoringRecord s JOIN s.subTask t JOIN t.project p " +
           "WHERE s.role = :role AND (s.reviewStatus = 'pending' OR " +
           "(s.reviewStatus IS NULL AND s.score IS NULL AND (s.aesthetics IS NULL OR s.innovation IS NULL))) " +
           "AND p.type = 'channel_custom' AND t.status = 'planner_approved' " +
           "AND p.salesId IN :userIds")
    long countPendingForSales(@Param("role") String role, @Param("userIds") List<String> userIds);

    @Query("SELECT COUNT(s) FROM ScoringRecord s JOIN s.subTask t JOIN t.project p " +
           "WHERE s.role = :role AND (s.reviewStatus = 'pending' OR " +
           "(s.reviewStatus IS NULL AND s.score IS NULL AND (s.aesthetics IS NULL OR s.innovation IS NULL))) " +
           "AND t.status = 'delivered' " +
           "AND (p.plannerId IN :userIds OR (p.type = 'channel_custom' AND p.status = 'pending_planner' " +
           "AND (p.plannerId IS NULL OR p.plannerId = '')))")
    long countPendingForPlanners(@Param("role") String role, @Param("userIds") List<String> userIds);
}
