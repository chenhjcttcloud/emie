package com.emie.designpm.service;

import com.emie.designpm.entity.NotificationAuditLog;
import com.emie.designpm.entity.NotificationEvent;
import com.emie.designpm.repository.NotificationAuditLogRepository;
import com.emie.designpm.repository.NotificationEventRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 第一阶段的可靠通知入口。调用方必须在原业务事务内发布事件，
 * 后续编排器将按收件人规则创建站内通知和渠道投递任务。
 */
@Service
public class NotificationOutboxService {

    private final NotificationEventRepository eventRepository;
    private final NotificationAuditLogRepository auditLogRepository;

    public NotificationOutboxService(NotificationEventRepository eventRepository,
                                    NotificationAuditLogRepository auditLogRepository) {
        this.eventRepository = eventRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public NotificationEvent publish(String eventType, String aggregateType, Long aggregateId,
                                     Integer aggregateVersion, String actorUserId,
                                     String idempotencyKey, String payload) {
        return eventRepository.findByIdempotencyKey(idempotencyKey).orElseGet(() -> saveNewEvent(
                eventType, aggregateType, aggregateId, aggregateVersion, actorUserId, idempotencyKey, payload));
    }

    private NotificationEvent saveNewEvent(String eventType, String aggregateType, Long aggregateId,
                                           Integer aggregateVersion, String actorUserId,
                                           String idempotencyKey, String payload) {
        try {
            // Notification 与审计都引用该事件；使用 saveAndFlush 保证 IDENTITY 主键已生成，
            // 避免在同一通知事务中写入空 event_id。
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
        } catch (DataIntegrityViolationException e) {
            return eventRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
        }
    }
}
