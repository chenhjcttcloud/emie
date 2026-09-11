package com.emie.designpm.feishu.repository;

import com.emie.designpm.entity.FeishuAttachmentCache;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeishuAttachmentCacheRepository extends JpaRepository<FeishuAttachmentCache, Long> {
    Optional<FeishuAttachmentCache> findByAppTokenAndStoredName(String appToken, String storedName);
}
