package com.emie.designpm.repository;

import com.emie.designpm.entity.ScoringRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ScoringRepository extends JpaRepository<ScoringRecord, Long> {

    List<ScoringRecord> findBySubTaskId(Long subTaskId);

    Optional<ScoringRecord> findBySubTaskIdAndRole(Long subTaskId, String role);

    /** 批量查询多个子任务的评分记录，减少 N+1 查询 */
    @Query("SELECT s FROM ScoringRecord s WHERE s.subTask.id IN ?1")
    List<ScoringRecord> findBySubTaskIds(List<Long> subTaskIds);

    void deleteBySubTaskId(Long subTaskId);
}
