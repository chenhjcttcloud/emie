package com.emie.designpm.background.repository;
import com.emie.designpm.entity.NotificationDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;
public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {
 List<NotificationDelivery> findTop50ByStatusInAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(List<String> statuses, LocalDateTime now);
}
