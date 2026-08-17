package com.emie.designpm.repository;
import com.emie.designpm.entity.PointAdjustmentLedger;import org.springframework.data.jpa.repository.*;import org.springframework.data.repository.query.Param;import java.util.*;import java.time.LocalDateTime;
public interface PointAdjustmentLedgerRepository extends JpaRepository<PointAdjustmentLedger,Long>{
 Optional<PointAdjustmentLedger> findBySourceTypeAndSourceId(String type,Long sourceId);
 List<PointAdjustmentLedger> findByUserIdOrderByCreatedAtDescIdDesc(String userId);
 /** 指定来源类型的最大 sourceId；无记录时返回 0。用于 MANUAL 手工调账生成自增 sourceId（唯一约束 (source_type,source_id) 兜底并发）。 */
 @Query("select coalesce(max(a.sourceId),0) from PointAdjustmentLedger a where a.sourceType=:sourceType")
 long maxSourceIdByType(@Param("sourceType") String sourceType);
 @Query("select coalesce(sum(a.points),0) from PointAdjustmentLedger a where a.userId=:userId") long sumPointsByUserId(@Param("userId")String userId);
 /** 月度归月口径与流水统一（P1-4）：指定月份按 accounting_month 归月，未指定月份时按入账时间区间归月（与 PointLedgerRepository.sumPerformancePointsByMonth 一致）。 */
 @Query("select a.userId, coalesce(sum(a.points), 0) from PointAdjustmentLedger a " +
         "where ((:month is not null and a.accountingMonth = :month) or " +
         " (:month is null and (:from is null or (a.createdAt >= :from and a.createdAt < :to)))) " +
         "group by a.userId")
 List<Object[]> sumPointsByMonth(@Param("month") String month, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

 /** 删除项目关联的调账记录：异议（APPEAL，按 appealId）与退单（TASK_WITHDRAWAL，按 withdrawalId）。 */
 @Modifying
 @Query("DELETE FROM PointAdjustmentLedger a WHERE " +
         "(a.sourceType = 'APPEAL' AND a.sourceId IN :appealIds) OR " +
         "(a.sourceType = 'TASK_WITHDRAWAL' AND a.sourceId IN :withdrawalIds)")
 void deleteProjectRelated(@Param("appealIds") Collection<Long> appealIds,
                           @Param("withdrawalIds") Collection<Long> withdrawalIds);
}
