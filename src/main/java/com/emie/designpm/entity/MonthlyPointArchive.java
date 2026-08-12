package com.emie.designpm.entity;
import jakarta.persistence.*; import lombok.*; import java.time.LocalDateTime;
@Data @NoArgsConstructor @Entity @Table(name="monthly_point_archives",uniqueConstraints=@UniqueConstraint(name="uk_monthly_archive_user",columnNames={"month_key","user_id"}))
public class MonthlyPointArchive {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(name="month_key",nullable=false,length=7) private String monthKey;
 @Column(name="user_id",nullable=false,length=100) private String userId;
 @Column(name="earned_points",nullable=false) private Integer earnedPoints=0;
 @Column(name="target_points",nullable=false) private Integer targetPoints=0;
 @Column(name="supplied_points",nullable=false) private Integer suppliedPoints=0;
 @Column(name="insufficient_supply_protection",nullable=false) private Boolean insufficientSupplyProtection=false;
 @Column(name="quarterly_average_points",nullable=false) private Double quarterlyAveragePoints=0d;
 @Column(nullable=false,length=20) private String status="DRAFT";
 @Column(name="archived_by",length=100) private String archivedBy;
 @Column(name="archived_at") private LocalDateTime archivedAt;
 @Column(name="created_at",nullable=false) private LocalDateTime createdAt;
 @Column(name="updated_at",nullable=false) private LocalDateTime updatedAt;
 @PrePersist void c(){createdAt=updatedAt=LocalDateTime.now();} @PreUpdate void u(){updatedAt=LocalDateTime.now();}
}
