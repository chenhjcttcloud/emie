package com.emie.designpm.repository;
import com.emie.designpm.entity.DesignerMarketEligibility;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
public interface DesignerMarketEligibilityRepository extends JpaRepository<DesignerMarketEligibility,Long>{
    Optional<DesignerMarketEligibility> findByUserId(String userId);
    /** 行锁读取（PESSIMISTIC_WRITE），防退单并发下 violation_count 读改写丢失更新。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM DesignerMarketEligibility e WHERE e.userId = :userId")
    Optional<DesignerMarketEligibility> findByUserIdForUpdate(@Param("userId") String userId);
}
