package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data @NoArgsConstructor @Entity
@Table(name="task_withdrawals", uniqueConstraints=@UniqueConstraint(name="uk_task_withdrawal_event", columnNames={"sub_task_id","created_at"}))
public class TaskWithdrawal {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="sub_task_id", nullable=false) private Long subTaskId;
    @Column(name="user_id", nullable=false, length=100) private String userId;
    @Column(name="elapsed_minutes", nullable=false) private Long elapsedMinutes;
    @Column(name="penalty_ratio", nullable=false) private Double penaltyRatio;
    @Column(name="penalty_points", nullable=false) private Integer penaltyPoints;
    @Column(nullable=false, length=500) private String reason;
    @Column(name="created_at", nullable=false) private LocalDateTime createdAt;
    @PrePersist void create(){ if(createdAt==null) createdAt=LocalDateTime.now(); }
}
