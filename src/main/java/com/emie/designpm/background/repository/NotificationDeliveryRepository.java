package com.emie.designpm.background.repository;
import com.emie.designpm.entity.NotificationDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {
 List<NotificationDelivery> findTop50ByStatusInAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(List<String> statuses, LocalDateTime now);
 @Query("SELECT d FROM NotificationDelivery d WHERE d.channel = 'feishu' ORDER BY d.id DESC")
 List<NotificationDelivery> findRecentFeishu();

 /**
  * 认领重试：状态 CAS（failed/pending -> processing），仅当行仍处于可重试状态时成功。
  * 返回受影响行数：1 表示本调用获得该投递的处理权，0 表示已被其它调度轮次/实例认领。
  */
 @Transactional
 @Modifying(clearAutomatically = true, flushAutomatically = true)
 @Query("UPDATE NotificationDelivery d SET d.status = 'processing', d.lastAttemptAt = :now " +
         "WHERE d.id = :id AND d.status IN ('failed', 'pending')")
 int claimForRetry(@Param("id") Long id, @Param("now") LocalDateTime now);

 /**
  * 恢复崩溃残留的认领：把长时间停留在 processing 的投递重置为 failed 并立即可重试。
  */
 @Transactional
 @Modifying
 @Query("UPDATE NotificationDelivery d SET d.status = 'failed', d.nextRetryAt = :now " +
         "WHERE d.status = 'processing' AND d.lastAttemptAt < :cutoff")
 int recoverStuckClaims(@Param("cutoff") LocalDateTime cutoff, @Param("now") LocalDateTime now);
}
