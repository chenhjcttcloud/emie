package com.emie.designpm.service;

import com.emie.designpm.entity.PointLedger;
import com.emie.designpm.entity.PointAdjustmentLedger;
import com.emie.designpm.entity.PointDifficultyConfig;
import com.emie.designpm.entity.PointRule;
import com.emie.designpm.entity.ScoringRecord;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.repository.PointLedgerRepository;
import com.emie.designpm.repository.PointAdjustmentLedgerRepository;
import com.emie.designpm.repository.PointDifficultyConfigRepository;
import com.emie.designpm.repository.PointRuleRepository;
import com.emie.designpm.repository.ScoringRepository;
import com.emie.designpm.repository.SystemConfigRepository;
import com.emie.designpm.entity.SystemConfig;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.time.YearMonth;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@Transactional
public class PointsService {
    public static final String TASK_APPROVED = "TASK_APPROVED";
    private static final Logger log = LoggerFactory.getLogger(PointsService.class);
    private final PointRuleRepository rules;
    private final PointLedgerRepository ledgers;
    private final ScoringRepository scoring;
    private final PointAdjustmentLedgerRepository adjustments;
    private final PointDifficultyConfigRepository difficulties;
    private final SystemConfigRepository configs;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 积分制度生效时间；发布前通过 POINTS_EFFECTIVE_AT 覆盖为正式上线时间。 */
    @Value("${points.effective-at:2026-08-14T00:00:00}")
    private String effectiveAtText = "2026-08-14T00:00:00";

    @Autowired
    public PointsService(PointRuleRepository rules, PointLedgerRepository ledgers, ScoringRepository scoring,
                         PointAdjustmentLedgerRepository adjustments, PointDifficultyConfigRepository difficulties,
                         SystemConfigRepository configs) {
        this.rules = rules; this.ledgers = ledgers; this.scoring = scoring;
        this.adjustments = adjustments; this.difficulties = difficulties; this.configs = configs;
    }

    /** Compatibility constructor for focused unit tests. */
    public PointsService(PointRuleRepository rules, PointLedgerRepository ledgers, ScoringRepository scoring) {
        this.rules = rules; this.ledgers = ledgers; this.scoring = scoring;
        this.adjustments = null; this.difficulties = null;
        this.configs = null;
    }

    /** Focused-test constructor for configurable difficulty behavior. */
    public PointsService(PointRuleRepository rules, PointLedgerRepository ledgers, ScoringRepository scoring,
                         PointDifficultyConfigRepository difficulties) {
        this.rules = rules; this.ledgers = ledgers; this.scoring = scoring;
        this.adjustments = null; this.difficulties = difficulties;
        this.configs = null;
    }

    /** 企划确认送审时立即发放基础积分。 */
    public void awardBaseSubmission(SubTask task) {
        if (!eligibleForPoints(task)) return;
        String ruleCode = normalizedRuleCode(task.getPointRuleCode());
        String ledgerCode = ruleCode + ":BASE";
        if (ledgers.existsByUserIdAndSubTaskIdAndRuleCode(task.getDesignerId(), task.getId(), ledgerCode)) return;
        ensureSnapshot(task, ruleCode);
        saveAward(task, ledgerCode, task.getBasePointSnapshot() * task.getDifficultyMultiplierSnapshot());
    }

    /** 最终验收后为 A/B 类按评分另发质量加分。 */
    public void awardQualityCompletion(SubTask task) {
        if (!eligibleForPoints(task)) return;
        String ruleCode = normalizedRuleCode(task.getPointRuleCode());
        // 归属月在每笔流水入账时锁定（见 saveRecipientAward）：BASE 在送审入账时按
        // 里程碑月/入账月落账，QUALITY 在本节点按实际完成月落账。
        // 严禁在此回溯改写同一子任务的既有流水（P1-3）：跨月任务（送审月≠完成月）若把
        // 已入账/已归档月份的 BASE 统一挪到完成月，会回溯改写已归档与已统计的月度数据，
        // 并与完成月统计重复错位。去掉改写后，各月统计按各自入账归属月取值，前后一致。
        awardBaseSubmission(task);
        String ledgerCode = ruleCode + ":QUALITY";
        if (ledgers.existsByUserIdAndSubTaskIdAndRuleCode(task.getDesignerId(), task.getId(), ledgerCode)) return;
        ensureSnapshot(task, ruleCode);
        // 质量阈值按页面展示的加权综合分判断（Σ(评分×权重)/Σ(权重)，与项目详情/设计师看板的加权综合一致），
        // 避免服务端简单平均与前端加权平均不一致，导致同一批数据服务端判定与页面展示矛盾。
        double weightedSum = 0d, totalWeight = 0d;
        for (ScoringRecord record : scoring.findBySubTaskId(task.getId())) {
            if (record.getScore() != null) {
                double weight = record.getWeight() == null ? 1d : record.getWeight();
                weightedSum += record.getScore() * weight;
                totalWeight += weight;
            }
        }
        double averageScore = totalWeight > 0 ? Math.round(weightedSum / totalWeight) : 0d;
        double ratio = averageScore >= task.getQualityTopThresholdSnapshot()
                ? task.getQualityTopRatioSnapshot()
                : averageScore >= task.getQualityBonusThresholdSnapshot() ? task.getQualityBonusRatioSnapshot() : 0d;
        if (ratio <= 0) return;
        double base = task.getBasePointSnapshot() * task.getDifficultyMultiplierSnapshot();
        double cap = task.getBasePointSnapshot() * task.getMaxTotalMultiplierSnapshot();
        saveAward(task, ledgerCode, Math.min(base * ratio, Math.max(0d, cap - base)));
    }

    private boolean eligibleForPoints(SubTask task) {
        if (task == null || task.getId() == null || task.getDesignerId() == null || task.getDesignerId().isBlank()) return false;
        // 持久化任务都会有创建时间；兼容旧的内存调用方/单元测试不阻断原有流程。
        if (task.getCreatedAt() == null) return true;
        try {
            return !task.getCreatedAt().isBefore(java.time.LocalDateTime.parse(effectiveAtText));
        } catch (Exception e) {
            // 生效时间配置解析失败时不再静默：记录配置值与受影响任务，便于上线前排查。
            log.warn("积分生效时间配置解析失败，跳过任务积分入账 effectiveAtText={} taskId={} detail={}",
                    effectiveAtText, task.getId(), e.getMessage());
            return false;
        }
    }

    private String completionMonth(SubTask task) {
        try {
            if (task.getActualDate() != null && !task.getActualDate().isBlank()) {
                return YearMonth.from(LocalDate.parse(task.getActualDate())).toString();
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** 兼容旧调用方；新流程实际在送审、最终验收两个节点分别调用。 */
    public void awardTaskApproval(SubTask task) { awardQualityCompletion(task); }

    private void ensureSnapshot(SubTask task, String ruleCode) {
        if (task.getBasePointSnapshot() == null || task.getDifficultyMultiplierSnapshot() == null
                || task.getQualityBonusThresholdSnapshot() == null
                || task.getQualityBonusRatioSnapshot() == null
                || task.getQualityTopThresholdSnapshot() == null
                || task.getQualityTopRatioSnapshot() == null
                || task.getMaxTotalMultiplierSnapshot() == null
                || task.getCountInPerformanceSnapshot() == null) {
            bindRuleSnapshot(task, ruleCode, task.getDifficultyCode());
        }
        if (task.getBasePointSnapshot() == null || task.getBasePointSnapshot() <= 0
                || task.getDifficultyMultiplierSnapshot() == null || task.getDifficultyMultiplierSnapshot() <= 0) {
            throw new IllegalStateException("任务积分快照无效");
        }
    }

    private void saveAward(SubTask task, String ledgerCode, double rawPoints) {
        List<Map<String, Object>> collaborators = collaboratorAllocations(task.getCollaboratorAllocationsJson());
        int collaboratorRatio = collaborators.stream().mapToInt(item -> ((Number) item.get("ratio")).intValue()).sum();
        double total = roundedPoints(rawPoints);
        double collaboratorTotal = 0d;
        for (Map<String, Object> collaborator : collaborators) {
            double share = roundedPoints(total * ((Number) collaborator.get("ratio")).intValue() / 100d);
            collaboratorTotal += share;
            saveRecipientAward(task, ledgerCode, String.valueOf(collaborator.get("userId")), share);
        }
        saveRecipientAward(task, ledgerCode, task.getDesignerId(), roundedPoints(total - collaboratorTotal));
    }

    private void saveRecipientAward(SubTask task, String ledgerCode, String userId, double rawPoints) {
        double awarded = roundedPoints(rawPoints);
        if (awarded <= 0 || userId == null || userId.isBlank()
                || ledgers.existsByUserIdAndSubTaskIdAndRuleCode(userId, task.getId(), ledgerCode)) return;
        PointLedger ledger = new PointLedger();
        ledger.setUserId(userId);
        ledger.setSubTaskId(task.getId());
        ledger.setRuleCode(ledgerCode);
        // 有效积分统一参与绩效统计；旧快照字段仅为历史兼容保留。
        ledger.setCountInPerformance(true);
        String completionMonth = completionMonth(task);
        ledger.setAccountingMonth(completionMonth != null ? completionMonth
                : task.getMilestoneMonth() == null || task.getMilestoneMonth().isBlank()
                ? YearMonth.now().toString() : task.getMilestoneMonth());
        ledger.setPoints(awarded);
        ledgers.save(ledger);
    }

    private double roundedPoints(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private List<Map<String, Object>> collaboratorAllocations(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {}); }
        catch (Exception e) { throw new IllegalStateException("合作积分比例快照无效"); }
    }

    /** Validate an enabled rule and freeze its point/multiplier values onto a task. */
    public void bindRuleSnapshot(SubTask task, String requestedRuleCode, String difficultyCode) {
        if (task == null) throw new IllegalArgumentException("子任务不能为空");
        // 试运行阶段兼容无积分历史/特殊任务；正式运行后新建或编辑任务必须绑定规则。
        if (requestedRuleCode == null || requestedRuleCode.isBlank()) {
            if (isPointRuleRequired()) throw new IllegalArgumentException("正式运行后必须选择积分规则");
            return;
        }
        String ruleCode = normalizedRuleCode(requestedRuleCode);
        PointRule rule = rules.findByRuleCode(ruleCode)
                .orElseThrow(() -> new IllegalArgumentException("积分规则不存在或已删除"));
        if (!rule.isEnabled()) throw new IllegalArgumentException("积分规则已停用，请重新选择");
        if (rule.getPoints() == null || rule.getPoints() < 0) throw new IllegalArgumentException("积分规则基础分无效");
        String normalizedDifficulty = "B1".equals(ruleCode) ? "COMPLEX" : normalizeDifficultyCode(difficultyCode);
        if (difficulties == null) throw new IllegalStateException("难度配置服务暂不可用");
        PointDifficultyConfig difficulty = difficulties.findByDifficultyCode(normalizedDifficulty)
                .orElseThrow(() -> new IllegalArgumentException("难度配置不存在或已删除"));
        if (!difficulty.isEnabled()) throw new IllegalArgumentException("难度配置已停用，请重新选择");
        double multiplier = difficulty.getMultiplier() == null ? 0d : difficulty.getMultiplier();
        if (!Double.isFinite(multiplier) || multiplier <= 0) throw new IllegalArgumentException("积分规则难度系数无效");
        task.setPointRuleCode(ruleCode);
        task.setDifficultyCode(normalizedDifficulty);
        task.setBasePointSnapshot(rule.getPoints());
        task.setDifficultyMultiplierSnapshot(multiplier);
        task.setQualityBonusThresholdSnapshot(rule.getQualityBonusThreshold() == null ? 0 : rule.getQualityBonusThreshold());
        task.setQualityBonusRatioSnapshot(rule.getQualityBonusRatio() == null ? 0d : rule.getQualityBonusRatio());
        task.setQualityTopThresholdSnapshot(rule.getQualityTopThreshold() == null ? 97 : rule.getQualityTopThreshold());
        task.setQualityTopRatioSnapshot(rule.getQualityTopRatio() == null ? 0.60d : rule.getQualityTopRatio());
        task.setMaxTotalMultiplierSnapshot(rule.getMaxTotalMultiplier() == null ? 3d : rule.getMaxTotalMultiplier());
        task.setCountInPerformanceSnapshot(rule.isCountInPerformance());
    }

    private boolean isPointRuleRequired() {
        if (configs == null) return false;
        String mode = configs.findByConfigKey("points.program.mode")
                .map(SystemConfig::getConfigValue).orElse("TRIAL").trim().toUpperCase();
        if ("ACTIVE".equals(mode)) return true;
        if (!"AUTO".equals(mode)) return false;
        String start = configs.findByConfigKey("points.program.active_start")
                .map(SystemConfig::getConfigValue).orElse("9999-12-31");
        try { return !LocalDate.now().isBefore(LocalDate.parse(start)); }
        catch (Exception ignored) { return false; }
    }

    private String normalizedRuleCode(String ruleCode) {
        return ruleCode == null || ruleCode.isBlank() ? TASK_APPROVED : ruleCode.trim().toUpperCase();
    }

    private String normalizeDifficultyCode(String difficultyCode) {
        return difficultyCode == null || difficultyCode.isBlank() ? "STANDARD" : difficultyCode.trim().toUpperCase();
    }

    @Transactional(readOnly = true)
    public double balance(String userId) { return ledgers.sumPointsByUserId(userId) + (adjustments == null ? 0 : adjustments.sumPointsByUserId(userId)); }

    @Transactional(readOnly = true)
    public List<PointLedger> ledger(String userId) { return ledgers.findByUserIdOrderByCreatedAtDescIdDesc(userId); }
    public Page<PointLedger> ledgerPage(String userId, Pageable pageable) { return ledgers.findByUserId(userId, pageable); }

    @Transactional(readOnly = true)
    public List<PointAdjustmentLedger> adjustmentLedger(String userId) { return adjustments == null ? List.of() : adjustments.findByUserIdOrderByCreatedAtDescIdDesc(userId); }

    @Transactional(readOnly = true)
    public List<PointRule> rules() { return rules.findAllByOrderByRuleCodeAsc(); }

    @Transactional(readOnly = true)
    public List<PointDifficultyConfig> difficulties() {
        return difficulties == null ? List.of() : difficulties.findAllByOrderByMultiplierAscDifficultyCodeAsc();
    }

    public PointDifficultyConfig updateDifficulty(String difficultyCode, Double multiplier,
                                                  Boolean enabled, String description) {
        if (difficulties == null) throw new IllegalStateException("难度配置服务暂不可用");
        String code = normalizeDifficultyCode(difficultyCode);
        PointDifficultyConfig difficulty = difficulties.findByDifficultyCode(code)
                .orElseThrow(() -> new IllegalArgumentException("难度配置不存在"));
        if (multiplier != null) {
            if (!Double.isFinite(multiplier) || multiplier <= 0 || multiplier > 10) {
                throw new IllegalArgumentException("难度系数必须大于0且不超过10");
            }
            difficulty.setMultiplier(multiplier);
        }
        if (enabled != null) difficulty.setEnabled(enabled);
        if (description != null) {
            String value = description.trim();
            difficulty.setDescription(value.substring(0, Math.min(value.length(), 255)));
        }
        return difficulties.save(difficulty);
    }

    public PointRule updateRule(String ruleCode, Integer points, Boolean enabled, String description,
                                String category, Double difficultyMultiplier, Integer qualityThreshold,
                                Double qualityRatio, Integer qualityTopThreshold, Double qualityTopRatio,
                                Double maxTotalMultiplier, Boolean countInPerformance) {
        PointRule rule = rules.findByRuleCode(ruleCode).orElseThrow(() -> new IllegalArgumentException("积分规则不存在"));
        if (points != null) {
            if (points < 0) throw new IllegalArgumentException("积分不能小于 0");
            rule.setPoints(points);
        }
        if (enabled != null) rule.setEnabled(enabled);
        if (description != null) rule.setDescription(description.trim().substring(0, Math.min(description.trim().length(), 255)));
        if (category != null) rule.setCategory(category.trim());
        if (difficultyMultiplier != null) { if (difficultyMultiplier < 0) throw new IllegalArgumentException("难度系数不能小于 0"); rule.setDifficultyMultiplier(difficultyMultiplier); }
        if (qualityThreshold != null) { if (qualityThreshold < 0) throw new IllegalArgumentException("质量阈值不能小于 0"); rule.setQualityBonusThreshold(qualityThreshold); }
        if (qualityRatio != null) { if (qualityRatio < 0) throw new IllegalArgumentException("质量加分比例不能小于 0"); rule.setQualityBonusRatio(qualityRatio); }
        if (qualityTopThreshold != null) { if (qualityTopThreshold < 0) throw new IllegalArgumentException("卓越质量阈值不能小于0"); rule.setQualityTopThreshold(qualityTopThreshold); }
        if (qualityTopRatio != null) { if (qualityTopRatio < 0) throw new IllegalArgumentException("卓越质量比例不能小于0"); rule.setQualityTopRatio(qualityTopRatio); }
        if (maxTotalMultiplier != null) { if (maxTotalMultiplier < 1) throw new IllegalArgumentException("总积分封顶倍数不能小于1"); rule.setMaxTotalMultiplier(maxTotalMultiplier); }
        if (countInPerformance != null) rule.setCountInPerformance(countInPerformance);
        return rules.save(rule);
    }

    public PointRule createRule(PointRule rule) {
        String code = normalizedRuleCode(rule == null ? null : rule.getRuleCode());
        if (TASK_APPROVED.equals(code) && (rule == null || rule.getRuleCode() == null || rule.getRuleCode().isBlank())) {
            throw new IllegalArgumentException("规则编号不能为空");
        }
        if (!code.matches("[A-Z0-9_-]{1,80}")) throw new IllegalArgumentException("规则编号仅支持字母、数字、下划线和短横线");
        if (rules.findByRuleCode(code).isPresent()) throw new IllegalArgumentException("积分规则编号已存在");
        if (rule.getPoints() == null || rule.getPoints() < 0) throw new IllegalArgumentException("积分不能小于0");
        rule.setId(null); rule.setRuleCode(code); rule.setEnabled(true);
        if (rule.getCategory() == null || rule.getCategory().isBlank()) rule.setCategory("GENERAL");
        return rules.save(rule);
    }

    public void deleteRule(String ruleCode) {
        PointRule rule = rules.findByRuleCode(normalizedRuleCode(ruleCode))
                .orElseThrow(() -> new IllegalArgumentException("积分规则不存在"));
        rules.delete(rule);
    }
}
