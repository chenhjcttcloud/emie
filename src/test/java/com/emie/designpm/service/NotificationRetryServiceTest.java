package com.emie.designpm.service;

import com.emie.designpm.background.repository.NotificationAuditLogRepository;
import com.emie.designpm.background.repository.NotificationDeliveryRepository;
import com.emie.designpm.background.repository.NotificationEventRepository;
import com.emie.designpm.background.repository.NotificationRepository;
import com.emie.designpm.background.repository.UserRepository;
import com.emie.designpm.entity.Notification;
import com.emie.designpm.entity.NotificationAuditLog;
import com.emie.designpm.entity.NotificationDelivery;
import com.emie.designpm.entity.User;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * NotificationRetryService 的 CAS 认领：claimForRetry 按受影响行数判定处理权，
 * 认领失败（0 行）跳过避免并发轮次重复发送；认领成功才处理并写回状态；
 * recoverStuckClaims 在每轮开始恢复 10 分钟以上仍停留在 processing 的崩溃残留。
 */
class NotificationRetryServiceTest {

    @Test
    void claimFailureSkipsDeliveryWithoutSendingAgain() throws Exception {
        Dependencies deps = new Dependencies();
        NotificationDelivery delivery = delivery(1L, "failed");
        when(deps.deliveries.findTop50ByStatusInAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
                eq(List.of("failed", "pending")), any())).thenReturn(List.of(delivery));
        // CAS 认领失败：其它调度轮次/实例已认领，本调用未获得处理权
        when(deps.deliveries.claimForRetry(eq(1L), any())).thenReturn(0);

        deps.service.retryDueDeliveries();

        verify(deps.deliveries).claimForRetry(eq(1L), any(LocalDateTime.class));
        verify(deps.feishu, never()).sendInteractiveMessage(anyString(), anyString());
        verify(deps.deliveries, never()).save(any(NotificationDelivery.class));
        verify(deps.audits, never()).save(any());
    }

    @Test
    void claimedDeliveryIsSentAndPersistedAsDelivered() throws Exception {
        Dependencies deps = new Dependencies();
        NotificationDelivery delivery = delivery(1L, "failed");
        Notification notification = new Notification();
        notification.setId(10L);
        notification.setEventId(5L);
        notification.setRecipientUserId("u-1");
        User user = new User();
        user.setUserId("u-1");
        user.setName("张三");
        user.setFeishuOpenId("ou_abc");
        when(deps.deliveries.findTop50ByStatusInAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
                eq(List.of("failed", "pending")), any())).thenReturn(List.of(delivery));
        when(deps.deliveries.claimForRetry(eq(1L), any())).thenReturn(1);
        when(deps.notifications.findByIdIn(List.of(10L))).thenReturn(List.of(notification));
        when(deps.users.findByUserIdIn(List.of("u-1"))).thenReturn(List.of(user));
        when(deps.feishu.sendInteractiveMessage("ou_abc", delivery.getCardPayload()))
                .thenReturn("msg-ext-1");

        deps.service.retryDueDeliveries();

        assertEquals("delivered", delivery.getStatus());
        assertEquals("msg-ext-1", delivery.getExternalMessageId());
        assertNotNull(delivery.getLastAttemptAt());
        assertNotNull(delivery.getDeliveredAt());
        assertNull(delivery.getNextRetryAt());
        verify(deps.deliveries).save(delivery);
        ArgumentCaptor<NotificationAuditLog> audit = ArgumentCaptor.forClass(NotificationAuditLog.class);
        verify(deps.audits).save(audit.capture());
        assertEquals("feishu_retry_delivered", audit.getValue().getAction());
        assertEquals("重试成功", audit.getValue().getDetail());
    }

    @Test
    void claimedDeliveryWithMissingNotificationGoesToDeadLetter() throws Exception {
        Dependencies deps = new Dependencies();
        NotificationDelivery delivery = delivery(1L, "failed");
        when(deps.deliveries.findTop50ByStatusInAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
                eq(List.of("failed", "pending")), any())).thenReturn(List.of(delivery));
        when(deps.deliveries.claimForRetry(eq(1L), any())).thenReturn(1);
        // 认领成功但通知本体已不存在：写回 dead_letter 而非静默丢失
        when(deps.notifications.findByIdIn(List.of(10L))).thenReturn(List.of());

        deps.service.retryDueDeliveries();

        assertEquals("dead_letter", delivery.getStatus());
        assertEquals("通知内容或卡片载荷不存在", delivery.getErrorMsg());
        assertNull(delivery.getNextRetryAt());
        verify(deps.deliveries).save(delivery);
        verify(deps.feishu, never()).sendInteractiveMessage(anyString(), anyString());
    }

    @Test
    void stuckProcessingClaimsAreRecoveredBeforeFetchingDue() {
        Dependencies deps = new Dependencies();
        when(deps.deliveries.findTop50ByStatusInAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
                eq(List.of("failed", "pending")), any())).thenReturn(List.of());

        deps.service.retryDueDeliveries();

        InOrder order = inOrder(deps.deliveries);
        order.verify(deps.deliveries).recoverStuckClaims(any(), any());
        order.verify(deps.deliveries).findTop50ByStatusInAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
                any(), any());
        verify(deps.deliveries, never()).claimForRetry(any(), any());

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(deps.deliveries).recoverStuckClaims(cutoff.capture(), any(LocalDateTime.class));
        // 恢复阈值应为「现在 - 10 分钟」，允许断言执行瞬间的微小偏差
        LocalDateTime expected = LocalDateTime.now().minusMinutes(10);
        assertTrue(cutoff.getValue().isAfter(expected.minusSeconds(5))
                && cutoff.getValue().isBefore(expected.plusSeconds(5)),
                "cutoff 应为 10 分钟前，实际: " + cutoff.getValue());
    }

    @Test
    void testRecipientOverridePreventsHistoricalRetriesFromReachingOriginalRecipients() {
        Dependencies deps = new Dependencies();
        when(deps.router.isTestOverrideEnabled()).thenReturn(true);

        deps.service.retryDueDeliveries();

        verifyNoInteractions(deps.deliveries, deps.notifications, deps.users, deps.feishu, deps.audits);
        assertThrows(IllegalStateException.class, () -> deps.service.retryNow(1L, "admin-1"));
    }

    private NotificationDelivery delivery(long id, String status) {
        return NotificationDelivery.builder()
                .id(id)
                .notificationId(10L)
                .channel("feishu")
                .status(status)
                .retryCount(1)
                .cardPayload("{\"schema\":\"2.0\",\"body\":{\"elements\":[]}}")
                .nextRetryAt(LocalDateTime.now())
                .build();
    }

    private static class Dependencies {
        private final NotificationDeliveryRepository deliveries = mock(NotificationDeliveryRepository.class);
        private final NotificationRepository notifications = mock(NotificationRepository.class);
        private final UserRepository users = mock(UserRepository.class);
        private final NotificationAuditLogRepository audits = mock(NotificationAuditLogRepository.class);
        private final FeishuBaseService feishu = mock(FeishuBaseService.class);
        private final NotificationEventRepository events = mock(NotificationEventRepository.class);
        private final NotificationRecipientRouter router = mock(NotificationRecipientRouter.class);
        private final NotificationRetryService service = new NotificationRetryService(
                deliveries, notifications, users, audits, feishu, events, router);
    }
}
