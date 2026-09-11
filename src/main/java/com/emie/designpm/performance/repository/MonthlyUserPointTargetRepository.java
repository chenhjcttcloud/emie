package com.emie.designpm.performance.repository;

import com.emie.designpm.entity.MonthlyUserPointTarget;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonthlyUserPointTargetRepository extends JpaRepository<MonthlyUserPointTarget, Long> {
    Optional<MonthlyUserPointTarget> findByMonthKeyAndUserId(String monthKey, String userId);

    Optional<MonthlyUserPointTarget> findByUserId(String userId);

    List<MonthlyUserPointTarget> findByMonthKeyOrderByUserNameAscUserIdAsc(String monthKey);
}
