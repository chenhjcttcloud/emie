package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

@Data
@Entity
@Table(name="material_market_items", uniqueConstraints=@UniqueConstraint(name="uk_material_project", columnNames="project_id"), indexes=@Index(name="idx_material_status", columnList="status"))
public class MaterialMarketItem {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false,length=200) private String title;
 @Column(nullable=false,length=100) private String creatorId;
 @Column(nullable=false,length=200) private String creatorName;
 @Column(nullable=false,length=100) private String ipName;
 @Column(nullable=false,length=20) private String category="visual";
 @Column(columnDefinition="TEXT") private String ipSubOptionsJson;
 @Column(columnDefinition="LONGTEXT",nullable=false) private String materialFilesJson;
 @Column(columnDefinition="LONGTEXT") private String referenceImagesJson;
 @Column(columnDefinition="TEXT",nullable=false) private String productDescription;
 @Column(columnDefinition="TEXT") private String proposalPptJson;
 @Column(nullable=false,length=20) private String status="available";
 private Long projectId;
 @Transient private String projectCode;
 private String selectedBy;
 @Transient private String selectedByName;
 private LocalDateTime selectedAt;
 @Column(length=20) private String adoptionType;
 @Column(nullable=false) private Integer likeCount=0;
 @Transient private boolean likedByCurrentUser;
 @Transient private List<MaterialMarketAdoption> adoptions=new ArrayList<>();
 @Column(nullable=false) private LocalDateTime createdAt;
 @PrePersist void onCreate(){if(createdAt==null)createdAt=LocalDateTime.now();}
 @JsonProperty("authorName") public String getAuthorName(){return creatorName;}
 @JsonProperty("description") public String getDescription(){return productDescription;}
 @JsonProperty("files") public String getFiles(){return materialFilesJson;}
 @JsonProperty("referenceImages") public String getReferenceImages(){return referenceImagesJson;}
 @JsonProperty("planFile") public String getPlanFile(){return proposalPptJson;}
 @JsonProperty("selected") public boolean isSelected(){return "selected".equals(status);}
}
