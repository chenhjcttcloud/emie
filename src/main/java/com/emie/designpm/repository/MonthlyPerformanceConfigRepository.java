package com.emie.designpm.repository;
import com.emie.designpm.entity.MonthlyPerformanceConfig; import org.springframework.data.jpa.repository.JpaRepository; import java.util.Optional;
public interface MonthlyPerformanceConfigRepository extends JpaRepository<MonthlyPerformanceConfig,Long>{ Optional<MonthlyPerformanceConfig> findByMonthKey(String monthKey); }
