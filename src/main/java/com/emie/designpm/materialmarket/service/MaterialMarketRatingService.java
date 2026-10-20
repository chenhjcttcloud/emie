package com.emie.designpm.materialmarket.service;

import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.entity.*;
import com.emie.designpm.materialmarket.repository.*;
import com.emie.designpm.points.repository.PointAdjustmentLedgerRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class MaterialMarketRatingService {
    private final MaterialMarketRatingRepository ratings;
    private final MaterialMarketItemRepository materials;
    private final UserRepository users;
    private final PointAdjustmentLedgerRepository adjustments;

    public MaterialMarketRatingService(
            MaterialMarketRatingRepository ratings,
            MaterialMarketItemRepository materials,
            UserRepository users,
            PointAdjustmentLedgerRepository adjustments) {
        this.ratings = ratings;
        this.materials = materials;
        this.users = users;
        this.adjustments = adjustments;
    }

    public MaterialMarketItem rate(Long id, String actorId, Object value) {
        if (!(value instanceof Number number)
                || !Double.isFinite(number.doubleValue())
                || number.doubleValue() < 0
                || number.doubleValue() > 5
                || number.doubleValue() != Math.rint(number.doubleValue()))
            throw new IllegalArgumentException("请选择0至5的整数星级");
        MaterialMarketItem item = materials.lockById(id).orElseThrow(() -> new NoSuchElementException("素材不存在"));
        if (!"free".equals(item.getPostType())) throw new IllegalStateException("仅自由单可以评星");
        if (!"available".equals(item.getStatus())) throw new IllegalStateException("已下架自由单不能评分");
        if (Objects.equals(item.getCreatorId(), actorId)) throw new SecurityException("不能给自己的自由单评分");
        if (!eligible(actorId)) throw new SecurityException("当前账号不能评星");
        MaterialMarketRating rating =
                ratings.findByMaterialIdAndUserId(id, actorId).orElseGet(MaterialMarketRating::new);
        rating.setMaterialId(id);
        rating.setUserId(actorId);
        rating.setStars(number.intValue());
        ratings.saveAndFlush(rating);
        decorate(List.of(item), actorId);
        if (item.getRatingStars() != null
                && adjustments
                        .findBySourceTypeAndSourceId("MATERIAL_FREE_RATING", id)
                        .isEmpty()) {
            PointAdjustmentLedger reward = new PointAdjustmentLedger();
            reward.setUserId(item.getCreatorId());
            reward.setSourceType("MATERIAL_FREE_RATING");
            reward.setSourceId(id);
            reward.setPoints(item.getRatingStars());
            reward.setReason("自由单「" + item.getTitle() + "」满5人评星奖励：" + item.getRatingStars() + "星（一次性结算）");
            reward.setCreatedBy(actorId);
            reward.setRuleCode("FREE_RATING");
            reward.setRuleDescription("满5人时按平均整星结算，每星1分，最高5分，后续评分不调整积分");
            adjustments.save(reward);
            item.setRatingRewardPoints(reward.getPoints().intValue());
        }
        return item;
    }

    private boolean eligible(String actorId) {
        return users.findByUserId(actorId)
                .map(user -> "active".equals(user.getStatus()))
                .orElse(false);
    }

    public void decorate(List<MaterialMarketItem> items, String actorId) {
        List<MaterialMarketItem> free =
                items.stream().filter(item -> "free".equals(item.getPostType())).toList();
        if (free.isEmpty()) return;
        boolean eligible = eligible(actorId);
        Map<Long, List<MaterialMarketRating>> grouped =
                ratings
                        .findByMaterialIdIn(
                                free.stream().map(MaterialMarketItem::getId).toList())
                        .stream()
                        .collect(java.util.stream.Collectors.groupingBy(MaterialMarketRating::getMaterialId));
        Map<Long, Integer> rewards = new HashMap<>();
        adjustments
                .findBySourceTypeAndSourceIdIn(
                        "MATERIAL_FREE_RATING",
                        free.stream().map(MaterialMarketItem::getId).toList())
                .forEach(reward ->
                        rewards.put(reward.getSourceId(), reward.getPoints().intValue()));
        for (MaterialMarketItem item : free) {
            item.setRatingRewardPoints(rewards.get(item.getId()));
            List<MaterialMarketRating> values = grouped.getOrDefault(item.getId(), List.of());
            item.setRatingStars(
                    values.size() < 5
                            ? null
                            : (int) Math.round(values.stream()
                                    .mapToInt(MaterialMarketRating::getStars)
                                    .average()
                                    .orElse(0)));
            item.setMyRating(values.stream()
                    .filter(rating -> Objects.equals(actorId, rating.getUserId()))
                    .map(MaterialMarketRating::getStars)
                    .findFirst()
                    .orElse(null));
            item.setCanRate(
                    eligible && !Objects.equals(actorId, item.getCreatorId()) && "available".equals(item.getStatus()));
        }
    }

    public void deleteByMaterialId(Long id) {
        ratings.deleteAllByMaterialId(id);
    }
}
