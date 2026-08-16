package com.emie.designpm.repository;

import com.emie.designpm.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Collection;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByRecipientUserIdAndStatusOrderByCreatedAtDesc(String recipientUserId, String status);
    long countByRecipientUserIdAndStatus(String recipientUserId, String status);

    List<Notification> findByIdIn(Collection<Long> ids);

    boolean existsByRecipientUserIdAndAggregateTypeAndAggregateIdAndTitleAndCreatedAtAfter(
            String recipientUserId, String aggregateType, Long aggregateId, String title,
            java.time.LocalDateTime createdAt);

    @Modifying
    @Query("DELETE FROM Notification n WHERE n.aggregateType = :aggregateType AND n.aggregateId IN :aggregateIds")
    void deleteByAggregateTypeAndAggregateIdIn(@Param("aggregateType") String aggregateType,
                                               @Param("aggregateIds") Collection<Long> aggregateIds);
}
