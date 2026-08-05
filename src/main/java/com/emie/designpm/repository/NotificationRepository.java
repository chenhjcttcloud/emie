package com.emie.designpm.repository;

import com.emie.designpm.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Collection;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByRecipientUserIdAndStatusOrderByCreatedAtDesc(String recipientUserId, String status);
    long countByRecipientUserIdAndStatus(String recipientUserId, String status);

    List<Notification> findByIdIn(Collection<Long> ids);

    boolean existsByRecipientUserIdAndAggregateTypeAndAggregateIdAndTitleAndCreatedAtAfter(
            String recipientUserId, String aggregateType, Long aggregateId, String title,
            java.time.LocalDateTime createdAt);
}
