package com.emie.designpm.performance.repository;

import com.emie.designpm.entity.MonthlyDesignerEvaluation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonthlyDesignerEvaluationRepository extends JpaRepository<MonthlyDesignerEvaluation, Long> {
    Optional<MonthlyDesignerEvaluation> findByUserIdAndMonthKey(String userId, String monthKey);

    List<MonthlyDesignerEvaluation> findByMonthKeyBetweenOrderByMonthKeyAscUserIdAsc(String start, String end);
}
