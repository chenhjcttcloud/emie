package com.emie.designpm.repository;
import com.emie.designpm.entity.PointAdjustmentLedger;import org.springframework.data.jpa.repository.*;import org.springframework.data.repository.query.Param;import java.util.*;import java.time.LocalDateTime;
public interface PointAdjustmentLedgerRepository extends JpaRepository<PointAdjustmentLedger,Long>{
 Optional<PointAdjustmentLedger> findBySourceTypeAndSourceId(String type,Long sourceId);
 List<PointAdjustmentLedger> findByUserIdOrderByCreatedAtDescIdDesc(String userId);
 @Query("select coalesce(sum(a.points),0) from PointAdjustmentLedger a where a.userId=:userId") long sumPointsByUserId(@Param("userId")String userId);
 @Query("select a.userId, coalesce(sum(a.points), 0) from PointAdjustmentLedger a where (:from is null or (a.createdAt >= :from and a.createdAt < :to)) group by a.userId")
 List<Object[]> sumPointsByPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

 /** 删除项目关联的调账记录：异议（APPEAL，按 appealId）与退单（TASK_WITHDRAWAL，按 withdrawalId）。 */
 @Modifying
 @Query("DELETE FROM PointAdjustmentLedger a WHERE " +
         "(a.sourceType = 'APPEAL' AND a.sourceId IN :appealIds) OR " +
         "(a.sourceType = 'TASK_WITHDRAWAL' AND a.sourceId IN :withdrawalIds)")
 void deleteProjectRelated(@Param("appealIds") Collection<Long> appealIds,
                           @Param("withdrawalIds") Collection<Long> withdrawalIds);
}
