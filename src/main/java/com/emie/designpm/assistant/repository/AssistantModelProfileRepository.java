package com.emie.designpm.assistant.repository;

import com.emie.designpm.entity.AssistantModelProfile;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssistantModelProfileRepository extends JpaRepository<AssistantModelProfile, String> {
    List<AssistantModelProfile> findAllByOrderByUpdatedAtDesc();
}
