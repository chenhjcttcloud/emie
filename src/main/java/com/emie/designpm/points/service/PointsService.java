package com.emie.designpm.points.service;

import com.emie.designpm.admin.repository.SystemConfigRepository;
import com.emie.designpm.entity.PointAdjustmentLedger;
import com.emie.designpm.entity.PointLedger;
import com.emie.designpm.entity.PointRule;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.entity.SubTaskDeliveryVersion;
import com.emie.designpm.entity.SystemConfig;
import com.emie.designpm.points.repository.PointAdjustmentLedgerRepository;
import com.emie.designpm.points.repository.PointLedgerRepository;
import com.emie.designpm.points.repository.PointRuleRepository;
import com.emie.designpm.scoring.repository.ScoringRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PointsService {
    private static final Logger log = LoggerFactory.getLogger(PointsService.class);
    private final PointRuleRepository rules;
    private final PointLedgerRepository ledgers;
    private final ScoringRepository scoring;
    private final PointAdjustmentLedgerRepository adjustments;
    private final SystemConfigRepository configs;
    private ConceptPointCapService conceptCap;

    @Autowired
    void setConceptCap(ConceptPointCapService conceptCap) {
        this.conceptCap = conceptCap;
    }

    @Autowired
    public PointsService(
            PointRuleRepository rules,
            PointLedgerRepository ledgers,
            ScoringRepository scoring,
            PointAdjustmentLedgerRepository adjustments,
            SystemConfigRepository configs) {
        this.rules = rules;
        this.ledgers = ledgers;
        this.scoring = scoring;
        this.adjustments = adjustments;
        this.configs = configs;
    }

    /** Compatibility constructor for focused unit tests. */
    public PointsService(PointRuleRepository rules, PointLedgerRepository ledgers, ScoringRepository scoring) {
        this.rules = rules;
        this.ledgers = ledgers;
        this.scoring = scoring;
        this.adjustments = null;
        this.configs = null;
    }

    /** 产品企划确认成果后发放基础分；既有流水保持不变。 */
    public void awardBaseSubmission(SubTask task) {
        awardBaseSubmission(task, null, null);
    }

    public void awardBaseSubmission(SubTask task, SubTaskDeliveryVersion version, String actorId) {
        if (!eligibleForPoints(task) || !"completed".equals(task.getStatus())) return;
        String ruleCode = normalizedRuleCode(task.getPointRuleCode());
        ensureSnapshot(task, ruleCode);
        saveRecipientAward(
                task, ruleCode + ":BASE", task.getDesignerId(), task.getBasePointSnapshot(), null, actorId, version);
    }

    /** 修改轮次在企划确认时入账，无积分轮只清理待办。 */
    public void awardPendingChangeBonus(SubTask task) {
        awardPendingChangeBonus(task, null, null);
    }

    public void awardPendingChangeBonus(SubTask task, SubTaskDeliveryVersion version, String actorId) {
        String requestId = task.getPendingChangeBonusRequestId();
        if (requestId == null || !"completed".equals(task.getStatus())) return;
        if (task.getId() == null
                || task.getDesignerId() == null
                || task.getDesignerId().isBlank()
                || !"designer".equals(task.getAssigneeRole())) throw new IllegalStateException("修改任务不符合积分条件");
        String ledgerCode = normalizedRuleCode(
                        task.getPendingChangeRuleCode() == null
                                ? task.getPointRuleCode()
                                : task.getPendingChangeRuleCode())
                + ":CHANGE:" + requestId;
        saveRecipientAward(
                task,
                ledgerCode,
                task.getDesignerId(),
                task.getPendingChangeBonusPoints() == null ? 0d : task.getPendingChangeBonusPoints(),
                task.getPendingChangeBonusReason(),
                actorId == null ? task.getPendingChangeBonusCreatedBy() : actorId,
                version);
        task.setPendingChangeBonusRequestId(null);
        task.setPendingChangeBonusPoints(null);
        task.setPendingChangeBonusReason(null);
        task.setPendingChangeBonusCreatedBy(null);
        task.setPendingChangeRuleCode(null);
    }

    /** 旧任务由企划在最终验收时手动给分，仍记录到任务积分流水并关联本次交付。 */
    public void awardLegacyManualApproval(SubTask task, SubTaskDeliveryVersion version, double points, String actorId) {
        if (task == null
                || task.getId() == null
                || task.getDesignerId() == null
                || task.getDesignerId().isBlank()) throw new IllegalArgumentException("旧任务缺少有效设计师，无法登记验收积分");
        String ruleCode = "LEGACY_MANUAL:APPROVAL";
        if (ledgers.existsByUserIdAndSubTaskIdAndRuleCode(task.getDesignerId(), task.getId(), ruleCode))
            throw new IllegalStateException("该旧任务已登记验收积分，不能重复给分");
        double awarded = roundedPoints(points);
        PointLedger ledger = new PointLedger();
        ledger.setUserId(task.getDesignerId());
        ledger.setSubTaskId(task.getId());
        ledger.setRuleCode(ruleCode);
        ledger.setCountInPerformance(true);
        ledger.setAccountingMonth(
                task.getMilestoneMonth() == null || task.getMilestoneMonth().isBlank()
                        ? YearMonth.now().toString()
                        : task.getMilestoneMonth());
        ledger.setPoints(awarded);
        ledger.setReason("旧任务验收手动积分：" + task.getName());
        ledger.setCreatedBy(actorId);
        ledger.setRuleDescription("企划验收手动给分");
        if (version != null) {
            version.setExpectedPoints(awarded);
            version.setPointRuleCode("LEGACY_MANUAL");
            version.setPointRuleDescription("企划验收手动给分");
            ledger.setDeliveryVersionId(version.getId());
            ledger.setSubmittedAt(version.getSubmittedAt());
            ledger.setConfirmedAt(version.getConfirmedAt());
        }
        ledgers.save(ledger);
    }

    public boolean baseAlreadyAwarded(SubTask task) {
        return ledgers.existsByUserIdAndSubTaskIdAndRuleCode(
                task.getDesignerId(), task.getId(), normalizedRuleCode(task.getPointRuleCode()) + ":BASE");
    }

    public String ruleDescription(String code) {
        return code == null
                ? null
                : rules.findByRuleCode(code).map(PointRule::getDescription).orElse(null);
    }

    public boolean changeBonusAlreadyAwarded(SubTask task, String requestId) {
        return ledgers.findBySubTaskId(task.getId()).stream()
                .anyMatch(ledger -> task.getDesignerId().equals(ledger.getUserId())
                        && ledger.getRuleCode().endsWith(":CHANGE:" + requestId));
    }

    /** 兼容历史调用方：不再计算质量加分。 */
    public void awardQualityCompletion(SubTask task) {
        awardBaseSubmission(task);
    }

    private boolean eligibleForPoints(SubTask task) {
        if (task == null
                || task.getId() == null
                || task.getDesignerId() == null
                || task.getDesignerId().isBlank()) return false;
        // 未配置积分规则的子任务不参与积分发放。
        if (task.getPointRuleCode() == null || task.getPointRuleCode().isBlank()) return false;
        // 积分仅面向设计师任务（产品确认：供应链等其它负责人类型不参与积分）。
        // 写入端（addSubTask/updateSubTask）已把 null/''、别名归一化为 designer；历史 NULL/别名数据按不发分处理。
        if (!"designer".equals(task.getAssigneeRole())) return false;
        // 不按项目/任务创建时间限制积分；所有符合条件的正式送审任务均可入账。
        return true;
    }

    private String completionMonth(SubTask task) {
        try {
            if (task.getActualDate() != null && !task.getActualDate().isBlank()) {
                return YearMonth.from(LocalDate.parse(task.getActualDate())).toString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 兼容旧调用方；新流程实际在送审、最终验收两个节点分别调用。 */
    public void awardTaskApproval(SubTask task) {
        awardQualityCompletion(task);
    }

    private void ensureSnapshot(SubTask task, String ruleCode) {
        if (task.getBasePointSnapshot() == null) bindRuleSnapshot(task, ruleCode);
        if (task.getBasePointSnapshot() == null
                || !Double.isFinite(task.getBasePointSnapshot())
                || task.getBasePointSnapshot() < 0) throw new IllegalStateException("任务积分快照无效");
    }

    private void saveAward(SubTask task, String ledgerCode, double rawPoints) {
        saveRecipientAward(task, ledgerCode, task.getDesignerId(), rawPoints);
    }

    private void saveRecipientAward(SubTask task, String ledgerCode, String userId, double rawPoints) {
        saveRecipientAward(task, ledgerCode, userId, rawPoints, null, null);
    }

    private void saveRecipientAward(
            SubTask task, String ledgerCode, String userId, double rawPoints, String reason, String createdBy) {
        saveRecipientAward(task, ledgerCode, userId, rawPoints, reason, createdBy, null);
    }

    private void saveRecipientAward(
            SubTask task,
            String ledgerCode,
            String userId,
            double rawPoints,
            String reason,
            String createdBy,
            SubTaskDeliveryVersion version) {
        if (version != null && version.getExpectedPoints() != null) {
            rawPoints = version.getExpectedPoints();
            userId = version.getSubmittedById();
            ledgerCode = version.getPointRuleCode()
                    + (version.getPointRequestId() == null ? ":BASE" : ":CHANGE:" + version.getPointRequestId());
        }
        double awarded = roundedPoints(rawPoints);
        if (awarded <= 0
                || userId == null
                || userId.isBlank()
                || ledgers.existsByUserIdAndSubTaskIdAndRuleCode(userId, task.getId(), ledgerCode)) return;
        PointLedger ledger = new PointLedger();
        ledger.setUserId(userId);
        ledger.setSubTaskId(task.getId());
        ledger.setRuleCode(ledgerCode);
        // 有效积分统一参与绩效统计；旧快照字段仅为历史兼容保留。
        ledger.setCountInPerformance(true);
        ledger.setAccountingMonth(accountingMonth(task));
        ledger.setPoints(awarded);
        ledger.setReason(reason);
        ledger.setCreatedBy(createdBy);
        if (version != null) {
            ledger.setDeliveryVersionId(version.getId());
            ledger.setSubmittedAt(version.getSubmittedAt());
            ledger.setConfirmedAt(version.getConfirmedAt());
            ledger.setRuleDescription(version.getPointRuleDescription());
        }
        ledgers.save(ledger);
    }

    private String accountingMonth(SubTask task) {
        String completed = completionMonth(task);
        return completed != null
                ? completed
                : task.getMilestoneMonth() == null || task.getMilestoneMonth().isBlank()
                        ? YearMonth.now().toString()
                        : task.getMilestoneMonth();
    }

    private double roundedPoints(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** Validate an enabled rule and freeze its base points onto a task. */
    public void bindRuleSnapshot(SubTask task, String requestedRuleCode) {
        if (task == null) throw new IllegalArgumentException("子任务不能为空");
        if (requestedRuleCode == null || requestedRuleCode.isBlank()) {
            throw new IllegalArgumentException("请选择积分规则");
        }
        String ruleCode = normalizedRuleCode(requestedRuleCode);
        PointRule rule = rules.findByRuleCode(ruleCode).orElseThrow(() -> new IllegalArgumentException("积分规则不存在或已删除"));
        if (!rule.isEnabled()) throw new IllegalArgumentException("积分规则已停用，请重新选择");
        if (rule.getPoints() == null || rule.getPoints() < 0) throw new IllegalArgumentException("积分规则基础分无效");
        task.setPointRuleCode(ruleCode);
        task.setBasePointSnapshot(rule.getPoints());
        task.setDifficultyMultiplierSnapshot(1d);
        task.setQualityBonusThresholdSnapshot(
                rule.getQualityBonusThreshold() == null ? 0 : rule.getQualityBonusThreshold());
        task.setQualityBonusRatioSnapshot(rule.getQualityBonusRatio() == null ? 0d : rule.getQualityBonusRatio());
        task.setQualityTopThresholdSnapshot(rule.getQualityTopThreshold() == null ? 97 : rule.getQualityTopThreshold());
        task.setQualityTopRatioSnapshot(rule.getQualityTopRatio() == null ? 0.60d : rule.getQualityTopRatio());
        task.setMaxTotalMultiplierSnapshot(rule.getMaxTotalMultiplier() == null ? 3d : rule.getMaxTotalMultiplier());
        task.setCountInPerformanceSnapshot(rule.isCountInPerformance());
    }

    private boolean isPointRuleRequired() {
        if (configs == null) return false;
        String mode = configs.findByConfigKey("points.program.mode")
                .map(SystemConfig::getConfigValue)
                .orElse("TRIAL")
                .trim()
                .toUpperCase();
        if ("ACTIVE".equals(mode)) return true;
        if (!"AUTO".equals(mode)) return false;
        String start = configs.findByConfigKey("points.program.active_start")
                .map(SystemConfig::getConfigValue)
                .orElse("9999-12-31");
        try {
            return !LocalDate.now().isBefore(LocalDate.parse(start));
        } catch (Exception ignored) {
            return false;
        }
    }

    private String normalizedRuleCode(String ruleCode) {
        return ruleCode == null ? "" : ruleCode.trim().toUpperCase();
    }

    @Transactional(readOnly = true)
    public double balance(String userId) {
        return ledgers.sumPointsByUserId(userId) + (adjustments == null ? 0 : adjustments.sumPointsByUserId(userId));
    }

    @Transactional(readOnly = true)
    public List<PointLedger> ledger(String userId) {
        return ledgers.findByUserIdOrderByCreatedAtDescIdDesc(userId);
    }

    public Page<PointLedger> ledgerPage(String userId, Pageable pageable) {
        return ledgers.findByUserId(userId, pageable);
    }

    @Transactional(readOnly = true)
    public List<PointAdjustmentLedger> adjustmentLedger(String userId) {
        return adjustments == null ? List.of() : adjustments.findByUserIdOrderByCreatedAtDescIdDesc(userId);
    }

    @Transactional(readOnly = true)
    public List<PointRule> rules() {
        return rules.findAllByOrderByRuleCodeAsc();
    }

    public PointRule updateRule(
            String ruleCode,
            Double points,
            Boolean enabled,
            String description,
            String category,
            String subcategory,
            Integer qualityThreshold,
            Double qualityRatio,
            Integer qualityTopThreshold,
            Double qualityTopRatio,
            Double maxTotalMultiplier,
            Boolean countInPerformance) {
        PointRule rule = rules.findByRuleCode(ruleCode).orElseThrow(() -> new IllegalArgumentException("积分规则不存在"));
        if (points != null) {
            if (points < 0) throw new IllegalArgumentException("积分不能小于 0");
            rule.setPoints(points);
        }
        if (enabled != null) rule.setEnabled(enabled);
        if (description != null)
            rule.setDescription(
                    description.trim().substring(0, Math.min(description.trim().length(), 255)));
        if (category != null) rule.setCategory(category.trim());
        if (subcategory != null) rule.setSubcategory(normalizedSubcategory(subcategory));
        if (qualityThreshold != null) {
            if (qualityThreshold < 0) throw new IllegalArgumentException("质量阈值不能小于 0");
            rule.setQualityBonusThreshold(qualityThreshold);
        }
        if (qualityRatio != null) {
            if (qualityRatio < 0) throw new IllegalArgumentException("质量加分比例不能小于 0");
            rule.setQualityBonusRatio(qualityRatio);
        }
        if (qualityTopThreshold != null) {
            if (qualityTopThreshold < 0) throw new IllegalArgumentException("卓越质量阈值不能小于0");
            rule.setQualityTopThreshold(qualityTopThreshold);
        }
        if (qualityTopRatio != null) {
            if (qualityTopRatio < 0) throw new IllegalArgumentException("卓越质量比例不能小于0");
            rule.setQualityTopRatio(qualityTopRatio);
        }
        if (maxTotalMultiplier != null) {
            if (maxTotalMultiplier < 1) throw new IllegalArgumentException("总积分封顶倍数不能小于1");
            rule.setMaxTotalMultiplier(maxTotalMultiplier);
        }
        if (countInPerformance != null) rule.setCountInPerformance(countInPerformance);
        return rules.save(rule);
    }

    public PointRule createRule(PointRule rule) {
        String code = normalizedRuleCode(rule == null ? null : rule.getRuleCode());
        if (code.isBlank()
                || rule == null
                || rule.getRuleCode() == null
                || rule.getRuleCode().isBlank()) {
            throw new IllegalArgumentException("规则编号不能为空");
        }
        if (!code.matches("[A-Z0-9_-]{1,80}")) throw new IllegalArgumentException("规则编号仅支持字母、数字、下划线和短横线");
        if (rules.findByRuleCode(code).isPresent()) throw new IllegalArgumentException("积分规则编号已存在");
        if (rule.getPoints() == null) rule.setPoints(0d);
        if (rule.getPoints() < 0) throw new IllegalArgumentException("积分不能小于0");
        rule.setSubcategory(normalizedSubcategory(rule.getSubcategory()));
        rule.setId(null);
        rule.setRuleCode(code);
        rule.setEnabled(true);
        if (rule.getCategory() == null || rule.getCategory().isBlank()) rule.setCategory("GENERAL");
        if (rule.getDescription() == null) rule.setDescription("");
        return rules.save(rule);
    }

    private String normalizedSubcategory(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.length() > 50) throw new IllegalArgumentException("二级分类不能超过50个字符");
        return trimmed;
    }

    public void deleteRule(String ruleCode) {
        PointRule rule = rules.findByRuleCode(normalizedRuleCode(ruleCode))
                .orElseThrow(() -> new IllegalArgumentException("积分规则不存在"));
        rules.delete(rule);
    }
}
