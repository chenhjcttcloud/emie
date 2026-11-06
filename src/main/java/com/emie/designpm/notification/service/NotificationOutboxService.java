package com.emie.designpm.notification.service;

import com.emie.designpm.entity.NotificationAuditLog;
import com.emie.designpm.entity.NotificationEvent;
import com.emie.designpm.notification.repository.NotificationAuditLogRepository;
import com.emie.designpm.notification.repository.NotificationEventRepository;
import java.time.LocalDateTime;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 第一阶段的可靠通知入口。调用方必须在原业务事务内发布事件，
 * 后续编排器将按收件人规则创建站内通知和渠道投递任务。
 */
@Service
public class NotificationOutboxService {

    private final NotificationEventRepository eventRepository;
    private final NotificationAuditLogRepository auditLogRepository;

    public NotificationOutboxService(
            NotificationEventRepository eventRepository, NotificationAuditLogRepository auditLogRepository) {
        this.eventRepository = eventRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public NotificationEvent publish(
            String eventType,
            String aggregateType,
            Long aggregateId,
            Integer aggregateVersion,
            String actorUserId,
            String idempotencyKey,
            String payload) {
        return eventRepository.findByIdempotencyKey(idempotencyKey).orElseGet(() -> {
            try {
                return publishNew(
                        eventType, aggregateType, aggregateId, aggregateVersion, actorUserId, idempotencyKey, payload);
            } catch (DataIntegrityViolationException e) {
                return eventRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
            }
        });
    }

    public boolean hasEvent(String idempotencyKey) {
        return eventRepository.existsByIdempotencyKey(idempotencyKey);
    }

    /** 只有成功插入幂等键的事务才能创建后续投递。并发冲突由调用方在回滚后处理。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public NotificationEvent publishNew(
            String eventType,
            String aggregateType,
            Long aggregateId,
            Integer aggregateVersion,
            String actorUserId,
            String idempotencyKey,
            String payload) {
        // 先分配主键并检测幂等冲突，再写入通知和审计。
        NotificationEvent event = eventRepository.saveAndFlush(NotificationEvent.builder()
                .eventType(eventType)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .aggregateVersion(aggregateVersion)
                .actorUserId(actorUserId)
                .idempotencyKey(idempotencyKey)
                .payload(payload)
                .status("pending")
                .occurredAt(LocalDateTime.now())
                .build());
        auditLogRepository.save(NotificationAuditLog.builder()
                .eventId(event.getId())
                .action("event_created")
                .operatorUserId(actorUserId)
                .detail("eventType=" + eventType + ", aggregate=" + aggregateType + "#" + aggregateId)
                .createdAt(LocalDateTime.now())
                .build());
        return event;
    }
}
