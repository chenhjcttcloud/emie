package com.emie.designpm.repository;

import com.emie.designpm.entity.DesignRequirementScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DesignRequirementScoreRepository extends JpaRepository<DesignRequirementScore, Long> {
    List<DesignRequirementScore> findByRequirementIdOrderByIdAsc(Long requirementId);

    @Query("SELECT s FROM DesignRequirementScore s JOIN FETCH s.requirement d " +
            "WHERE (s.reviewerId = :userId OR (s.reviewerId IS NULL AND s.role = :role)) " +
            "AND s.status <> 'waiting' ORDER BY CASE WHEN s.status = 'pending' THEN 0 ELSE 1 END, d.deadline ASC, s.id DESC")
    List<DesignRequirementScore> findVisibleForReviewer(@Param("role") String role, @Param("userId") String userId);
}
