package com.emie.designpm.repository;
import com.emie.designpm.entity.PointAppeal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
public interface PointAppealRepository extends JpaRepository<PointAppeal,Long>{
 boolean existsByPointLedgerIdAndApplicantUserIdAndStatusIn(Long ledgerId,String userId,List<String> statuses);
 boolean existsByPointLedgerIdAndApplicantUserIdAndStatus(Long ledgerId,String userId,String status);
 List<PointAppeal> findByApplicantUserIdOrderByCreatedAtDesc(String userId);
 List<PointAppeal> findAllByOrderByCreatedAtDesc();

 @Query("SELECT a FROM PointAppeal a WHERE a.pointLedgerId IN :ledgerIds")
 List<PointAppeal> findByPointLedgerIdIn(@Param("ledgerIds") Collection<Long> ledgerIds);

 @Modifying
 @Query("DELETE FROM PointAppeal a WHERE a.pointLedgerId IN :ledgerIds")
 void deleteByPointLedgerIds(@Param("ledgerIds") Collection<Long> ledgerIds);
}
