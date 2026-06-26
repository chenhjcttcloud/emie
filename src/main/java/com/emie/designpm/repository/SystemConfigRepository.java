package com.emie.designpm.repository;

import com.emie.designpm.entity.SystemConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SystemConfigRepository extends JpaRepository<SystemConfig, Long> {

    /** 按配置分组查询 */
    List<SystemConfig> findByConfigGroupOrderBySortOrderAsc(String configGroup);

    /** 按键名查询 */
    Optional<SystemConfig> findByConfigKey(String configKey);

    /** 获取所有分组名（去重） */
    // 通过原生查询获取分组列表
}
