package com.emie.designpm.repository;

import com.emie.designpm.entity.NotificationDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {
    Optional<NotificationDelivery> findByNotificationIdAndChannel(Long notificationId, String channel);
}
