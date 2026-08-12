package com.emie.designpm.entity;
import jakarta.persistence.*; import lombok.*; import java.time.LocalDateTime;
@Data @NoArgsConstructor @Entity @Table(name="po_monthly_progress",uniqueConstraints=@UniqueConstraint(name="uk_po_progress_month",columnNames={"po_project_id","month_key"}))
public class PoMonthlyProgress {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(name="po_project_id",nullable=false) private Long poProjectId;
 @Column(name="month_key",nullable=false,length=7) private String monthKey;
 @Column(nullable=false,length=4000) private String summary;
 @Column(nullable=false,length=24) private String status="SUBMITTED";
 @Column(name="submitted_by",nullable=false,length=100) private String submittedBy;
 @Column(name="submitted_at",nullable=false) private LocalDateTime submittedAt;
 @Column(name="confirmed_by",length=100) private String confirmedBy;
 @Column(name="confirmed_at") private LocalDateTime confirmedAt;
 @Column(name="review_comment",length=1000) private String reviewComment;
 @PrePersist void c(){if(submittedAt==null)submittedAt=LocalDateTime.now();}
}
