package com.emie.designpm.repository;

import com.emie.designpm.entity.PermissionVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface PermissionVersionRepository extends JpaRepository<PermissionVersion, Long> {
    Optional<PermissionVersion> findBySubjectTypeAndSubjectKey(String subjectType, String subjectKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PermissionVersion> findForUpdateBySubjectTypeAndSubjectKey(String subjectType, String subjectKey);
}
