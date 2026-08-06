package com.emie.designpm.background.repository;
import com.emie.designpm.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
public interface NotificationRepository extends JpaRepository<Notification, Long> { java.util.List<Notification> findByIdIn(Collection<Long> ids); }
