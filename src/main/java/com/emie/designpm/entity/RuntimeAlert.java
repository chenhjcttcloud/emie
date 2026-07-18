package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

/** 运行时告警状态，保存首次告警、最近一次告警和恢复时间。 */
@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "runtime_alerts", uniqueConstraints = @UniqueConstraint(name = "uk_runtime_alert_type", columnNames = "alert_type"),
        indexes = @Index(name = "idx_runtime_alert_status_time", columnList = "status,last_seen_at"))
public class RuntimeAlert {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "alert_type", nullable = false, length = 60) private String alertType;
    @Column(nullable = false, length = 20) private String status;
    @Column(columnDefinition = "LONGTEXT") private String detail;
    @Column(name = "first_seen_at", nullable = false) private LocalDateTime firstSeenAt;
    @Column(name = "last_seen_at", nullable = false) private LocalDateTime lastSeenAt;
    @Column(name = "recovered_at") private LocalDateTime recoveredAt;
}
