package com.emie.designpm.materialmarket.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.entity.*;
import com.emie.designpm.materialmarket.repository.*;
import com.emie.designpm.points.repository.PointAdjustmentLedgerRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@ContextConfiguration(classes = MaterialMarketRatingPersistenceTest.Persistence.class)
class MaterialMarketRatingPersistenceTest {
    @Configuration
    @EnableJpaRepositories(
            basePackageClasses = {MaterialMarketRatingRepository.class, PointAdjustmentLedgerRepository.class})
    @EntityScan(basePackageClasses = MaterialMarketItem.class)
    static class Persistence {}

    @Autowired
    MaterialMarketRatingRepository ratings;

    @Autowired
    MaterialMarketItemRepository materials;

    @Autowired
    PointAdjustmentLedgerRepository adjustments;

    @Test
    void firstFiveDistinctRatersSettleOnceAndSubsequentChangesPreservePoints() {
        UserRepository users = mock(UserRepository.class);
        when(users.findByUserId(anyString()))
                .thenAnswer(call -> Optional.of(User.builder()
                        .userId(call.getArgument(0))
                        .role("designer")
                        .build()));
        MaterialMarketRatingService service = new MaterialMarketRatingService(ratings, materials, users, adjustments);
        MaterialMarketItem item = new MaterialMarketItem();
        item.setTitle("数据库回归");
        item.setPostType("free");
        item.setCreatorId("author");
        item.setCreatorName("作者");
        item.setMaterialFilesJson("[]");
        item.setProductDescription("测试");
        materials.saveAndFlush(item);
        Long id = item.getId();
        for (int i = 0; i < 4; i++) service.rate(id, "user" + i, 5);
        service.rate(id, "user0", 5);
        assertEquals(4, ratings.count());
        assertNull(item.getRatingStars());
        assertEquals(0, adjustments.count());
        service.rate(id, "user4", 0);
        assertEquals(4, item.getRatingStars());
        assertEquals(4.0, adjustments.sumPointsByUserId("author"));
        for (int i = 0; i < 5; i++) service.rate(id, "user" + i, 0);
        assertEquals(0, item.getRatingStars());
        assertEquals(4, item.getRatingRewardPoints());
        assertEquals(1, adjustments.count());
        assertEquals(4.0, adjustments.sumPointsByUserId("author"));
        assertEquals(5, ratings.count());
    }
}
