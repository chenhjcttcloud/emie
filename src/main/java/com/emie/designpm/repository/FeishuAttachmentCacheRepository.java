package com.emie.designpm.repository;

import com.emie.designpm.entity.FeishuAttachmentCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FeishuAttachmentCacheRepository extends JpaRepository<FeishuAttachmentCache, Long> {
    Optional<FeishuAttachmentCache> findByAppTokenAndStoredName(String appToken, String storedName);
}
