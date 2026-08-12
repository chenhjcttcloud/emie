package com.emie.designpm.entity;
import jakarta.persistence.*; import lombok.*; import java.time.LocalDateTime;
@Data @NoArgsConstructor @Entity @Table(name="po_point_ledgers",uniqueConstraints=@UniqueConstraint(name="uk_po_ledger_progress",columnNames="progress_id"))
public class PoPointLedger {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(name="progress_id",nullable=false) private Long progressId;
 @Column(name="po_project_id",nullable=false) private Long poProjectId;
 @Column(name="user_id",nullable=false,length=100) private String userId;
 @Column(name="month_key",nullable=false,length=7) private String monthKey;
 @Column(nullable=false) private Integer points;
 @Column(name="created_by",nullable=false,length=100) private String createdBy;
 @Column(name="created_at",nullable=false) private LocalDateTime createdAt;
 @PrePersist void c(){if(createdAt==null)createdAt=LocalDateTime.now();}
}
