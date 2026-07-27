package com.emie.designpm.repository;

import com.emie.designpm.entity.PermissionAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PermissionAuditLogRepository extends JpaRepository<PermissionAuditLog, Long> {
    List<PermissionAuditLog> findTop100ByTargetTypeOrderByCreatedAtDesc(String targetType);
    List<PermissionAuditLog> findTop50ByTargetTypeAndTargetKeyOrderByCreatedAtDesc(String targetType, String targetKey);
}
