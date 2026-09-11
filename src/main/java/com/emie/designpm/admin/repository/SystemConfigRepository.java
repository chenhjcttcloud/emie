package com.emie.designpm.admin.repository;

import com.emie.designpm.entity.SystemConfig;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemConfigRepository extends JpaRepository<SystemConfig, Long> {

    /** 按配置分组查询 */
    List<SystemConfig> findByConfigGroupOrderBySortOrderAsc(String configGroup);

    /** 按键名查询 */
    Optional<SystemConfig> findByConfigKey(String configKey);

    /** 获取所有分组名（去重） */
    // 通过原生查询获取分组列表
}
