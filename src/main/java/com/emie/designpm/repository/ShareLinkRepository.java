package com.emie.designpm.repository;

import com.emie.designpm.entity.ShareLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ShareLinkRepository extends JpaRepository<ShareLink, Long> {

    Optional<ShareLink> findByToken(String token);

    List<ShareLink> findByCreatedByOrderByCreatedAtDesc(String createdBy);

    List<ShareLink> findByStatusAndExpiresAtBefore(String status, java.time.LocalDateTime now);

    @Modifying
    @Query("update ShareLink s set s.viewCount = s.viewCount + 1 where s.id = :id and (s.maxViews = 0 or s.viewCount < s.maxViews) and s.status = 'active'")
    int incrementViewCountIfAvailable(@Param("id") Long id);
}
