package com.emie.designpm.repository;

import com.emie.designpm.entity.PointLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PointLedgerRepository extends JpaRepository<PointLedger, Long> {
    boolean existsByUserIdAndSubTaskIdAndRuleCode(String userId, Long subTaskId, String ruleCode);
    List<PointLedger> findByUserIdOrderByCreatedAtDescIdDesc(String userId);

    @Query("select coalesce(sum(l.points), 0) from PointLedger l where l.userId = :userId")
    double sumPointsByUserId(@Param("userId") String userId);

    @Query("select coalesce(sum(l.points), 0) from PointLedger l where l.userId = :userId and l.countInPerformance = true")
    double sumPerformancePointsByUserId(@Param("userId") String userId);
}
