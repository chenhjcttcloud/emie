package com.emie.designpm.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 后台任务专用连接池基础设施。默认关闭，待后台 Repository/事务迁移完成后再启用，
 * 避免在迁移未完成时出现“配置看似隔离、实际仍走主池”的假隔离状态。
 */
@Configuration
@ConditionalOnProperty(name = "app.db-pool-isolation.enabled", havingValue = "true")
public class BackgroundDataSourceConfig {
    @Bean(name = "backgroundDataSource", destroyMethod = "close")
    @ConfigurationProperties(prefix = "app.db-pool-isolation.background")
    public HikariDataSource backgroundDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean(name = "backgroundJdbcTemplate")
    public JdbcTemplate backgroundJdbcTemplate(HikariDataSource backgroundDataSource) {
        return new JdbcTemplate(backgroundDataSource);
    }
}
