package com.emie.designpm.points.repository;

import com.emie.designpm.entity.MonthlyPointArchive;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonthlyPointArchiveRepository extends JpaRepository<MonthlyPointArchive, Long> {
    Optional<MonthlyPointArchive> findByMonthKeyAndUserId(String month, String userId);

    List<MonthlyPointArchive> findByUserIdOrderByMonthKeyDesc(String userId);

    List<MonthlyPointArchive> findByMonthKeyOrderByUserId(String month);
}
