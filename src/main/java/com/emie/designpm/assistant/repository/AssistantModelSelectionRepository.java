package com.emie.designpm.assistant.repository;

import com.emie.designpm.entity.AssistantModelSelection;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AssistantModelSelectionRepository extends JpaRepository<AssistantModelSelection, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AssistantModelSelection s where s.id = 1")
    Optional<AssistantModelSelection> findForUpdate();
}
