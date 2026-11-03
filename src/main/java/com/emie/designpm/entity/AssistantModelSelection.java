package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "assistant_model_selection")
@Getter
@Setter
public class AssistantModelSelection {
    @Id
    private Long id;

    @Column(nullable = false, length = 64)
    private String activeProfileId;

    /** Null inherits the existing server enable switch until an administrator changes it. */
    private Boolean enabled;
}
