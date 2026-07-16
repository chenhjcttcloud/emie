package com.emie.designpm.repository;

import com.emie.designpm.entity.NotificationAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationAuditLogRepository extends JpaRepository<NotificationAuditLog, Long> {
}
