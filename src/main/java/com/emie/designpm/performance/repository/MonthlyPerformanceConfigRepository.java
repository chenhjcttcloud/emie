package com.emie.designpm.performance.repository;

import com.emie.designpm.entity.MonthlyPerformanceConfig;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonthlyPerformanceConfigRepository extends JpaRepository<MonthlyPerformanceConfig, Long> {
    Optional<MonthlyPerformanceConfig> findByMonthKey(String monthKey);
}
