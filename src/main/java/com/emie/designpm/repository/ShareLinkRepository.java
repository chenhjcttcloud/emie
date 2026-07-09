package com.emie.designpm.repository;

import com.emie.designpm.entity.ShareLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShareLinkRepository extends JpaRepository<ShareLink, Long> {

    Optional<ShareLink> findByToken(String token);

    List<ShareLink> findByCreatedByOrderByCreatedAtDesc(String createdBy);

    List<ShareLink> findByStatusAndExpiresAtBefore(String status, java.time.LocalDateTime now);
}
