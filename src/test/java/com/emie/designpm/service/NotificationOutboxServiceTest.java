package com.emie.designpm.service;

import com.emie.designpm.entity.NotificationAuditLog;
import com.emie.designpm.entity.NotificationEvent;
import com.emie.designpm.repository.NotificationAuditLogRepository;
import com.emie.designpm.repository.NotificationEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationOutboxServiceTest {

    @Mock
    private NotificationEventRepository eventRepository;
    @Mock
    private NotificationAuditLogRepository auditLogRepository;
    @InjectMocks
    private NotificationOutboxService outboxService;
    @Captor
    private ArgumentCaptor<NotificationEvent> eventCaptor;

    @Test
    void publishesOnePendingEventAndAuditForNewBusinessState() {
        when(eventRepository.findByIdempotencyKey("TASK_DELIVERED:task:12:v3"))
                .thenReturn(Optional.empty());
        when(eventRepository.saveAndFlush(any(NotificationEvent.class))).thenAnswer(invocation -> {
            NotificationEvent event = invocation.getArgument(0);
            event.setId(9L);
            return event;
        });

        NotificationEvent event = outboxService.publish(
                "TASK_DELIVERED", "sub_task", 12L, 3, "designer_lee",
                "TASK_DELIVERED:task:12:v3", "{\"deliveryCount\":1}");

        assertEquals(9L, event.getId());
        assertEquals("pending", event.getStatus());
        verify(eventRepository).saveAndFlush(eventCaptor.capture());
        assertEquals(3, eventCaptor.getValue().getAggregateVersion());
        verify(auditLogRepository).save(any(NotificationAuditLog.class));
    }

    @Test
    void returnsExistingEventForTechnicalRetryWithoutCreatingAnotherAudit() {
        NotificationEvent existing = NotificationEvent.builder().id(7L).status("pending").build();
        when(eventRepository.findByIdempotencyKey("TASK_REDELIVERED:task:12:v4"))
                .thenReturn(Optional.of(existing));

        NotificationEvent result = outboxService.publish(
                "TASK_REDELIVERED", "sub_task", 12L, 4, "designer_lee",
                "TASK_REDELIVERED:task:12:v4", "{\"deliveryCount\":2}");

        assertSame(existing, result);
        verify(eventRepository, never()).saveAndFlush(any());
        verifyNoInteractions(auditLogRepository);
    }
}
