package com.emie.designpm.admin.repository;

import com.emie.designpm.entity.RuntimeAlert;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuntimeAlertRepository extends JpaRepository<RuntimeAlert, Long> {
    Optional<RuntimeAlert> findByAlertType(String alertType);
}
