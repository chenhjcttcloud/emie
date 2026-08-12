package com.emie.designpm.repository;
import com.emie.designpm.entity.PoPointLedger; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface PoPointLedgerRepository extends JpaRepository<PoPointLedger,Long>{
 Optional<PoPointLedger> findByProgressId(Long progressId); List<PoPointLedger> findByUserIdOrderByCreatedAtDesc(String userId);
}
