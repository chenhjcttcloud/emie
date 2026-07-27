package com.emie.designpm.repository;

import com.emie.designpm.entity.PermissionDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PermissionDefinitionRepository extends JpaRepository<PermissionDefinition, Long> {
    Optional<PermissionDefinition> findByCode(String code);
    List<PermissionDefinition> findByEnabledTrueOrderByModuleAscCodeAsc();
}
