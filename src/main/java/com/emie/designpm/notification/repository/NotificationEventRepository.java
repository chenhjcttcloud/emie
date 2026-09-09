package com.emie.designpm.notification.repository;

import com.emie.designpm.entity.NotificationEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NotificationEventRepository extends JpaRepository<NotificationEvent, Long> {
    Optional<NotificationEvent> findByIdempotencyKey(String idempotencyKey);
}
