package com.emie.designpm.repository;

import com.emie.designpm.entity.ScoringRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ScoringRepository extends JpaRepository<ScoringRecord, Long> {

    List<ScoringRecord> findBySubTaskId(Long subTaskId);

    Optional<ScoringRecord> findBySubTaskIdAndRole(Long subTaskId, String role);

    /** 批量查询多个子任务的评分记录，减少 N+1 查询 */
    @Query("SELECT s FROM ScoringRecord s WHERE s.subTask.id IN ?1")
    List<ScoringRecord> findBySubTaskIds(List<Long> subTaskIds);

    /** 批量查询多个项目的所有评分记录（通过 JOIN） */
    @Query("SELECT s FROM ScoringRecord s JOIN s.subTask t JOIN t.project p WHERE p.id IN ?1")
    List<ScoringRecord> findByProjectIds(List<Long> projectIds);

    void deleteBySubTaskId(Long subTaskId);

    @Modifying
    @Query("DELETE FROM ScoringRecord s WHERE s.subTask.id IN (SELECT t.id FROM SubTask t WHERE t.project.id = ?1)")
    void deleteByProjectId(Long projectId);

    @Query("SELECT COUNT(s) FROM ScoringRecord s WHERE s.role = ?1 AND s.score IS NULL AND (s.aesthetics IS NULL OR s.innovation IS NULL)")
    long countPendingByRole(String role);

    @Query("SELECT COUNT(s) FROM ScoringRecord s JOIN s.subTask t JOIN t.project p " +
           "WHERE p.type = 'channel_custom' AND s.role IN ('sales', 'planner') " +
           "AND s.score IS NULL AND (s.aesthetics IS NULL OR s.innovation IS NULL)")
    long countPendingChannelScore();

    @Query("SELECT COUNT(s) FROM ScoringRecord s WHERE s.score IS NULL AND (s.aesthetics IS NULL OR s.innovation IS NULL)")
    long countAllPendingScores();
}
