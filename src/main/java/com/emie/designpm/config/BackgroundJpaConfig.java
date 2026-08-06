package com.emie.designpm.config;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty(name = "app.db-pool-isolation.enabled", havingValue = "true")
@EnableJpaRepositories(basePackages = "com.emie.designpm.background.repository",
        entityManagerFactoryRef = "backgroundEntityManagerFactory",
        transactionManagerRef = "backgroundTransactionManager")
public class BackgroundJpaConfig {
    @Bean(name = "backgroundEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean backgroundEntityManagerFactory(
            EntityManagerFactoryBuilder builder, DataSource backgroundDataSource) {
        return builder.dataSource(backgroundDataSource)
                .packages("com.emie.designpm.entity")
                .persistenceUnit("background")
                .build();
    }

    @Bean(name = "backgroundTransactionManager")
    public PlatformTransactionManager backgroundTransactionManager(
            EntityManagerFactory backgroundEntityManagerFactory) {
        return new JpaTransactionManager(backgroundEntityManagerFactory);
    }
}
