package com.emie.designpm.repository;
import com.emie.designpm.entity.PoMonthlyProgress; import org.springframework.data.jpa.repository.JpaRepository; import org.springframework.data.jpa.repository.Lock; import org.springframework.data.jpa.repository.Query; import org.springframework.data.repository.query.Param; import jakarta.persistence.LockModeType; import java.util.*;
public interface PoMonthlyProgressRepository extends JpaRepository<PoMonthlyProgress,Long>{
 Optional<PoMonthlyProgress> findByPoProjectIdAndMonthKey(Long poProjectId,String monthKey);
 List<PoMonthlyProgress> findBySubmittedByOrderBySubmittedAtDesc(String userId);
 List<PoMonthlyProgress> findAllByOrderBySubmittedAtDesc();
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from PoMonthlyProgress p where p.id=:id") Optional<PoMonthlyProgress> findLockedById(@Param("id") Long id);
}
