package com.emie.designpm.admin.repository;

import com.emie.designpm.entity.PermissionDefinition;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionDefinitionRepository extends JpaRepository<PermissionDefinition, Long> {
    Optional<PermissionDefinition> findByCode(String code);

    List<PermissionDefinition> findByEnabledTrueOrderByModuleAscCodeAsc();
}
