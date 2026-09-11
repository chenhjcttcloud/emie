package com.emie.designpm.notification.repository;

import com.emie.designpm.entity.NotificationEvent;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationEventRepository extends JpaRepository<NotificationEvent, Long> {
    Optional<NotificationEvent> findByIdempotencyKey(String idempotencyKey);
}
