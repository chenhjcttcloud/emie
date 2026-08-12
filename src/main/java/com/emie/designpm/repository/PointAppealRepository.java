package com.emie.designpm.repository;
import com.emie.designpm.entity.PointAppeal; import org.springframework.data.jpa.repository.JpaRepository; import java.util.List;
public interface PointAppealRepository extends JpaRepository<PointAppeal,Long>{
 boolean existsByPointLedgerIdAndApplicantUserIdAndStatusIn(Long ledgerId,String userId,List<String> statuses);
 List<PointAppeal> findByApplicantUserIdOrderByCreatedAtDesc(String userId);
 List<PointAppeal> findAllByOrderByCreatedAtDesc();
}
