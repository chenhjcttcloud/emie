package com.emie.designpm.points.repository;

import com.emie.designpm.entity.PoPointLedger;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PoPointLedgerRepository extends JpaRepository<PoPointLedger, Long> {
    Optional<PoPointLedger> findByProgressId(Long progressId);

    List<PoPointLedger> findByUserIdOrderByCreatedAtDesc(String userId);
}
