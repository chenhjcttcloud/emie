package com.emie.designpm.background.repository;

import com.emie.designpm.entity.Notification;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    java.util.List<Notification> findByIdIn(Collection<Long> ids);
}
