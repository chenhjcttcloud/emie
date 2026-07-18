package com.emie.designpm.repository;

import com.emie.designpm.entity.RuntimeAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RuntimeAlertRepository extends JpaRepository<RuntimeAlert, Long> {
    Optional<RuntimeAlert> findByAlertType(String alertType);
}
