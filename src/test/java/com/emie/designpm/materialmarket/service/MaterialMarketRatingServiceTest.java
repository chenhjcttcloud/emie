package com.emie.designpm.materialmarket.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.entity.*;
import com.emie.designpm.materialmarket.repository.*;
import com.emie.designpm.points.repository.PointAdjustmentLedgerRepository;
import java.util.*;
import org.junit.jupiter.api.Test;

class MaterialMarketRatingServiceTest {
    final MaterialMarketRatingRepository ratings = mock(MaterialMarketRatingRepository.class);
    final MaterialMarketItemRepository materials = mock(MaterialMarketItemRepository.class);
    final UserRepository users = mock(UserRepository.class);
    final PointAdjustmentLedgerRepository adjustments = mock(PointAdjustmentLedgerRepository.class);
    final MaterialMarketRatingService service = new MaterialMarketRatingService(ratings, materials, users, adjustments);

    MaterialMarketItem item() {
        MaterialMarketItem item = new MaterialMarketItem();
        item.setId(1L);
        item.setPostType("free");
        item.setCreatorId("author");
        when(materials.lockById(1L)).thenReturn(Optional.of(item));
        when(users.findByUserId("rater"))
                .thenReturn(Optional.of(
                        User.builder().userId("rater").role("designer").build()));
        return item;
    }

    List<MaterialMarketRating> votes(int count, int stars) {
        List<MaterialMarketRating> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MaterialMarketRating rating = new MaterialMarketRating();
            rating.setMaterialId(1L);
            rating.setUserId("user" + i);
            rating.setStars(stars);
            result.add(rating);
        }
        return result;
    }

    @Test
    void hidesAverageUntilFiveDistinctVotesAndRoundsWholeStars() {
        MaterialMarketItem item = item();
        List<MaterialMarketRating> votes = votes(4, 4);
        when(ratings.findByMaterialIdIn(any())).thenReturn(votes);
        service.decorate(List.of(item), "rater");
        assertNull(item.getRatingStars());
        MaterialMarketRating zero = new MaterialMarketRating();
        zero.setMaterialId(1L);
        zero.setUserId("rater");
        zero.setStars(0);
        votes.add(zero);
        service.decorate(List.of(item), "rater");
        assertEquals(3, item.getRatingStars());
        assertEquals(0, item.getMyRating());
        assertTrue(item.isCanRate());
    }

    @Test
    void previouslyPublishedFreePostCanBeRatedByAnotherDesigner() throws Exception {
        MaterialMarketItem item = item();
        item.setCreatedAt(java.time.LocalDateTime.of(2026, 8, 1, 12, 0));
        when(ratings.findByMaterialIdIn(any())).thenReturn(List.of());
        service.decorate(List.of(item), "rater");
        assertTrue(item.isCanRate());
        var json = new com.fasterxml.jackson.databind.ObjectMapper()
                .findAndRegisterModules()
                .valueToTree(item);
        assertTrue(json.get("canRate").asBoolean());
        assertTrue(json.get("ratingStars").isNull());
        service.rate(1L, "rater", 4);
        verify(ratings).saveAndFlush(any());
        service.decorate(List.of(item), "author");
        assertFalse(item.isCanRate());
    }

    @Test
    void acceptsZeroAndUpdatesExistingVoteInsteadOfAddingVoter() {
        item();
        MaterialMarketRating existing = votes(1, 5).getFirst();
        existing.setUserId("rater");
        existing.setId(9L);
        when(ratings.findByMaterialIdAndUserId(1L, "rater")).thenReturn(Optional.of(existing));
        when(ratings.findByMaterialIdIn(any())).thenReturn(List.of(existing));
        service.rate(1L, "rater", 0);
        assertEquals(0, existing.getStars());
        verify(ratings).saveAndFlush(existing);
    }

    @Test
    void rejectsInvalidValuesAndUnauthorizedRatings() {
        MaterialMarketItem item = item();
        for (Object value : List.of(-1, 6, 0.5, "5", Double.NaN))
            assertThrows(IllegalArgumentException.class, () -> service.rate(1L, "rater", value));
        assertThrows(IllegalArgumentException.class, () -> service.rate(1L, "rater", null));
        assertThrows(SecurityException.class, () -> service.rate(1L, "author", 5));
        when(users.findByUserId("rater"))
                .thenReturn(Optional.of(
                        User.builder().role("sales").status("disabled").build()));
        assertThrows(SecurityException.class, () -> service.rate(1L, "rater", 5));
        item.setPostType("idea");
        assertThrows(IllegalStateException.class, () -> service.rate(1L, "rater", 5));
        item.setPostType("free");
        item.setStatus("withdrawn");
        assertThrows(IllegalStateException.class, () -> service.rate(1L, "rater", 5));
        verify(ratings, never()).saveAndFlush(any());
    }

    @Test
    void settlesOnlyAtFifthVoteAndNeverChangesRewardAfterwards() {
        MaterialMarketItem item = item();
        item.setTitle("创意");
        List<MaterialMarketRating> votes = votes(4, 5);
        when(ratings.findByMaterialIdIn(any())).thenReturn(votes);
        service.rate(1L, "rater", 5);
        verify(adjustments, never()).save(any());
        votes.addAll(votes(1, 0));
        service.rate(1L, "rater", 0);
        var capture = org.mockito.ArgumentCaptor.forClass(PointAdjustmentLedger.class);
        verify(adjustments).save(capture.capture());
        PointAdjustmentLedger reward = capture.getValue();
        assertEquals(4.0, reward.getPoints());
        assertEquals("author", reward.getUserId());
        assertEquals("MATERIAL_FREE_RATING", reward.getSourceType());
        when(adjustments.findBySourceTypeAndSourceId("MATERIAL_FREE_RATING", 1L))
                .thenReturn(Optional.of(reward));
        when(ratings.findByMaterialIdIn(any())).thenReturn(votes(6, 0));
        service.rate(1L, "rater", 0);
        assertEquals(0, item.getRatingStars());
        assertEquals(4.0, reward.getPoints());
        verify(adjustments, times(1)).save(any());
    }

    @Test
    void allActiveRolesCanRateIncludingSalesAndFiveStars() {
        MaterialMarketItem item = item();
        when(users.findByUserId("rater"))
                .thenReturn(Optional.of(User.builder().role("sales").build()));
        when(ratings.findByMaterialIdIn(any())).thenReturn(votes(5, 5));
        new MaterialMarketRatingService(ratings, materials, users, adjustments).rate(1L, "rater", 5);
        assertEquals(5, item.getRatingStars());
        assertTrue(item.isCanRate());
    }
}
