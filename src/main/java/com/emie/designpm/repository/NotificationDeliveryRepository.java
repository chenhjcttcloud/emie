package com.emie.designpm.repository;

import com.emie.designpm.entity.NotificationDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.time.LocalDateTime;
import java.util.List;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {
    Optional<NotificationDelivery> findByNotificationIdAndChannel(Long notificationId, String channel);

    List<NotificationDelivery> findTop50ByStatusInAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
            List<String> statuses, LocalDateTime now);

    @Query("SELECT d FROM NotificationDelivery d WHERE d.channel = 'feishu' AND d.status IN :statuses ORDER BY d.id DESC")
    List<NotificationDelivery> findRecentFeishuByStatuses(@Param("statuses") List<String> statuses);
}
