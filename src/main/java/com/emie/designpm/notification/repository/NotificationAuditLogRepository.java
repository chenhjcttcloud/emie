package com.emie.designpm.notification.repository;

import com.emie.designpm.entity.NotificationAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationAuditLogRepository extends JpaRepository<NotificationAuditLog, Long> {
}
