package com.emie.designpm.performance.service;

import static org.junit.jupiter.api.Assertions.*;

import com.emie.designpm.config.BackgroundDataSourceConfig;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

class MonthlyPointReportDataSourceTest {
    @Test
    void poolIsolationKeepsMonthlyReportsOnPrimaryDataSourceEvenWhenAutoConfiguredTemplateUsesBackground() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ConfigurationPropertiesAutoConfiguration.class, JdbcTemplateAutoConfiguration.class))
                .withUserConfiguration(MainDataSource.class, BackgroundDataSourceConfig.class)
                .withPropertyValues(
                        "app.db.pool.isolation.enabled=true",
                        "app.db.pool.isolation.background.jdbc-url=jdbc:h2:mem:background_" + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "app.db.pool.isolation.background.username=sa",
                        "app.db.pool.isolation.background.password=",
                        "app.db.pool.isolation.background.maximum-pool-size=1")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var primary = context.getBean("dataSource", DataSource.class);
                    var background = context.getBean("backgroundDataSource", DataSource.class);
                    assertNotSame(primary, background);

                    // Boot reuses the only JdbcTemplate, which belongs to the background pool.
                    var autoConfigured = context.getBean(NamedParameterJdbcTemplate.class);
                    assertSame(background, autoConfigured.getJdbcTemplate().getDataSource());
                    var reports = context.getBean(MonthlyPointReportService.class);
                    var reportingJdbc = (NamedParameterJdbcTemplate) ReflectionTestUtils.getField(reports, "jdbc");
                    assertNotNull(reportingJdbc);
                    assertSame(primary, reportingJdbc.getJdbcTemplate().getDataSource());

                    var primaryJdbc = new JdbcTemplate(primary);
                    primaryJdbc.execute("CREATE TABLE monthly_report_primary_probe(id INT)");
                    primaryJdbc.update("INSERT INTO monthly_report_primary_probe VALUES(1)");
                    assertEquals(
                            1,
                            reportingJdbc.queryForObject(
                                    "SELECT COUNT(*) FROM monthly_report_primary_probe", Map.of(), Integer.class));
                    assertThrows(
                            BadSqlGrammarException.class,
                            () -> autoConfigured.queryForObject(
                                    "SELECT COUNT(*) FROM monthly_report_primary_probe", Map.of(), Integer.class));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MonthlyPointReportService.class)
    static class MainDataSource {
        @Bean(name = "dataSource")
        @Primary
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:primary_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }
    }
}
