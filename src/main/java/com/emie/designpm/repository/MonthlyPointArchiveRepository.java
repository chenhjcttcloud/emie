package com.emie.designpm.repository;
import com.emie.designpm.entity.MonthlyPointArchive; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface MonthlyPointArchiveRepository extends JpaRepository<MonthlyPointArchive,Long>{
 Optional<MonthlyPointArchive> findByMonthKeyAndUserId(String month,String userId);
 List<MonthlyPointArchive> findByUserIdOrderByMonthKeyDesc(String userId);
 List<MonthlyPointArchive> findByMonthKeyOrderByUserId(String month);
}
