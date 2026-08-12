package com.emie.designpm.repository;

import com.emie.designpm.entity.MonthlyUserPointTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface MonthlyUserPointTargetRepository extends JpaRepository<MonthlyUserPointTarget, Long> {
    Optional<MonthlyUserPointTarget> findByMonthKeyAndUserId(String monthKey, String userId);
    List<MonthlyUserPointTarget> findByMonthKeyOrderByUserNameAscUserIdAsc(String monthKey);
}
