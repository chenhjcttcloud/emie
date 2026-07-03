package com.emie.designpm.config;

import com.emie.designpm.entity.ComplianceItem;
import com.emie.designpm.entity.PriceRange;
import com.emie.designpm.entity.ProductCategory;
import com.emie.designpm.repository.ComplianceItemRepository;
import com.emie.designpm.repository.PriceRangeRepository;
import com.emie.designpm.repository.ProductCategoryRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataSeeder {

    @Bean
    CommandLineRunner seedProductCategories(ProductCategoryRepository repo) {
        return args -> {
            if (repo.count() > 0) return;

            repo.save(new ProductCategory("灯", 1));
            repo.save(new ProductCategory("音响", 2));
            repo.save(new ProductCategory("相机", 3));
            repo.save(new ProductCategory("个护", 4));
            repo.save(new ProductCategory("其他", 5));
        };
    }

    @Bean
    CommandLineRunner seedComplianceItems(ComplianceItemRepository repo) {
        return args -> {
            if (repo.count() > 0) return;

            repo.save(new ComplianceItem("蓝牙", 1));
            repo.save(new ComplianceItem("无线发射", 2));
            repo.save(new ComplianceItem("电子电气", 3));
            repo.save(new ComplianceItem("电池充电", 4));
            repo.save(new ComplianceItem("包装铭牌", 5));
            repo.save(new ComplianceItem("渠道上架", 6));
            repo.save(new ComplianceItem("儿童相关", 7));
        };
    }

    @Bean
    CommandLineRunner seedPriceRanges(PriceRangeRepository repo) {
        return args -> {
            if (repo.count() > 0) return;

            repo.save(new PriceRange("100元以下", 1));
            repo.save(new PriceRange("150元以下", 2));
            repo.save(new PriceRange("200元以下", 3));
            repo.save(new PriceRange("250元以下", 4));
            repo.save(new PriceRange("300元以下", 5));
            repo.save(new PriceRange("350元以下", 6));
            repo.save(new PriceRange("350元以上", 7));
        };
    }
}
