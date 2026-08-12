package com.emie.designpm.repository;
import com.emie.designpm.entity.PointAdjustmentLedger;import org.springframework.data.jpa.repository.*;import org.springframework.data.repository.query.Param;import java.util.*;
public interface PointAdjustmentLedgerRepository extends JpaRepository<PointAdjustmentLedger,Long>{
 Optional<PointAdjustmentLedger> findBySourceTypeAndSourceId(String type,Long sourceId);
 List<PointAdjustmentLedger> findByUserIdOrderByCreatedAtDescIdDesc(String userId);
 @Query("select coalesce(sum(a.points),0) from PointAdjustmentLedger a where a.userId=:userId") long sumPointsByUserId(@Param("userId")String userId);
}
