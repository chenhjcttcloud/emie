package com.emie.designpm.admin.service;

import java.util.List;
import java.util.Map;

/**
 * 工作量口径与关注等级判定。由 {@link AdminWorkloadService} 抽出：
 * 查询负责「攞数」，呢度负责「点解读」—— 同批完成率同关注等级两套规则集中喺一处，
 * 前端只负责显示，唔再各自计一套。
 */
final class AdminWorkloadMetrics {

    private AdminWorkloadMetrics() {}

    /** 读取时间线数组的第 i 列，缺列当 0（唔同角色嘅 SQL 列数唔一样）。 */
    private static long at(long[] counts, int index) {
        return counts != null && counts.length > index ? counts[index] : 0L;
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * 把子任务台账嘅数累加落成员统计。用「累加」唔用「覆盖」，因为销售嗰边
     * 已经放咗项目层面嘅数，佢哋嘅工作量系「项目 + 承接／发布嘅子任务」。
     */
    static void putTaskLedger(Map<String, Object> us, AdminWorkloadTaskLedger.Tally tally) {
        if (tally == null) {
            us.putIfAbsent("tasksCreated", 0L);
            us.putIfAbsent("tasksChannel", 0L);
            us.putIfAbsent("tasksRegular", 0L);
            us.putIfAbsent("tasksCompletedFromCreated", 0L);
            for (String key :
                    List.of("difficultyStandard", "difficultyComplex", "difficultyMajor", "difficultyUnset")) {
                us.putIfAbsent(key, 0L);
            }
            return;
        }
        // 子任务层面单独出数，唔同项目层面混埋一齐（表格要分开显示）
        us.put("tasksCreated", tally.created);
        us.put("tasksChannel", tally.channelCreated);
        us.put("tasksRegular", tally.regularCreated);
        us.put("tasksCompletedFromCreated", tally.completedFromCreated);
        us.put("difficultyStandard", tally.difficultyStandard);
        us.put("difficultyComplex", tally.difficultyComplex);
        us.put("difficultyMajor", tally.difficultyMajor);
        us.put("difficultyUnset", tally.difficultyUnset);
        us.put("tasksOutstandingChannel", tally.outstandingChannel);
        us.put("tasksOutstandingRegular", tally.outstandingRegular);
        us.put("outstandingStandard", tally.outstandingStandard);
        us.put("outstandingComplex", tally.outstandingComplex);
        us.put("outstandingMajor", tally.outstandingMajor);
        us.put("outstandingUnset", tally.outstandingUnset);
        // 旧字段：项目角色（销售）已经写咗项目数，唔好覆盖；任务角色先由呢度填
        us.putIfAbsent("channelCustomProjects", tally.channelCreated);
        us.putIfAbsent("regularProjects", tally.regularCreated);
        us.putIfAbsent("completedChannelProjects", tally.channelCompleted);
        us.putIfAbsent("completedRegularProjects", tally.regularCompleted);
        add(us, "created", tally.created);
        add(us, "completed", tally.completedInRange);
        add(us, "createdCompleted", tally.completedFromCreated);
        add(us, "outstanding", tally.outstandingTotal());
        add(us, "outstandingOwn", tally.outstandingOwn);
        add(us, "outstandingWaiting", tally.outstandingWaiting);
    }

    /**
     * 期末未完结项目按状态归入「自己要做」定「等他人」。
     * 已终止嘅项目唔算积压（terminated 冇 completed_at，唔特别处理就会永远挂喺度）。
     */
    static void addProjectOutstanding(Map<String, Object> us, Map<String, Long> byStatus, boolean plannerView) {
        if (byStatus == null) return;
        long own = 0;
        long waiting = 0;
        for (Map.Entry<String, Long> entry : byStatus.entrySet()) {
            WorkloadStatusBoundary.Bucket bucket = plannerView
                    ? WorkloadStatusBoundary.forProjectPlanner(entry.getKey())
                    : WorkloadStatusBoundary.forProjectOwner(entry.getKey());
            if (bucket == WorkloadStatusBoundary.Bucket.DONE) continue;
            if (bucket == WorkloadStatusBoundary.Bucket.OWN) own += entry.getValue();
            else waiting += entry.getValue();
        }
        add(us, "outstanding", own + waiting);
        add(us, "outstandingOwn", own);
        add(us, "outstandingWaiting", waiting);
    }

    /** 在手项目按渠道定制／常规品分类。 */
    static void putProjectTypeMix(Map<String, Object> us, Map<String, Long> byType) {
        long channel = 0;
        long regular = 0;
        if (byType != null) {
            for (Map.Entry<String, Long> entry : byType.entrySet()) {
                if ("channel_custom".equals(entry.getKey())) channel += entry.getValue();
                else regular += entry.getValue();
            }
        }
        us.put("projectsOutstandingChannel", channel);
        us.put("projectsOutstandingRegular", regular);
    }

    private static void add(Map<String, Object> us, String key, long delta) {
        us.put(key, longValue(us.get(key)) + delta);
    }

    /** 写入设计/送审需求嘅同批口径：本期新增，以及当中已完成嘅数量。 */
    static void putDesignRequirementCohort(Map<String, Object> us, long[] cohort) {
        us.put("designRequirementsCreated", at(cohort, 0));
        us.put("designRequirementsCompletedFromCreated", at(cohort, 1));
    }

    /**
     * 汇总成员嘅统一口径三件套：
     * workloadCreated（本期新增嘅工作量）、workloadCompletedFromCreated（当中已完成）、completionRate。
     * 分子分母系同一批嘢，所以唔会出现完成率爆 100% 要封顶嘅情况。
     * 另外 completedInRange 系「本期完成（包含往期开嘅）」，系另一个指标，唔攞嚟做分子。
     */
    static void putWorkloadTotals(Map<String, Object> us) {
        long created = longValue(us.get("created"))
                + longValue(us.get("assigned"))
                + longValue(us.get("designRequirementsCreated"));
        long done = longValue(us.get("createdCompleted")) + longValue(us.get("designRequirementsCompletedFromCreated"));
        done = Math.min(done, created);
        us.put("workloadCreated", created);
        us.put("workloadCompletedFromCreated", done);
        us.put("completionRate", created == 0 ? null : Math.round(done * 1000.0 / created) / 10.0);
        us.put("completedInRange", longValue(us.get("completed")) + longValue(us.get("completedDesignRequirements")));
        // 未完成需求当「自己要做」：冇人验收之说，就系设计师手上嘅活
        long requirementsOutstanding = longValue(us.get("designRequirementsOutstanding"));
        us.put("outstandingOwnTotal", longValue(us.get("outstandingOwn")) + requirementsOutstanding);
        us.put("outstandingWaitingTotal", longValue(us.get("outstandingWaiting")));
        us.put("outstandingTotal", longValue(us.get("outstanding")) + requirementsOutstanding);
    }

    /**
     * 为同一角色内嘅成员标注关注等级。
     *
     * <p>阈值用本角色「在手未完成」嘅 P75（下限 5），而唔系一刀切嘅绝对值 ——
     * 唔同角色嘅任务颗粒度差好远（销售数项目、设计师数子任务），绝对值会误判。
     *
     * <ul>
     *   <li>idle：本期冇新增，亦冇在手 —— 唔系问题，只系冇工作量
     *   <li>risk（需关注）：在手 ≥ 阈值，而且本期新增消化率 &lt; 60%（或本期完全冇新增）
     *   <li>watch（留意）：在手 ≥ 阈值，或者本期新增 ≥ 3 但消化率 &lt; 40%
     *   <li>steady（正常）：其余
     * </ul>
     */
    static void applyWorkloadStatus(List<Map<String, Object>> userStats) {
        // 阈值只睇「自己要做」：等验收／等评分嘅积压唔应该算落承接人头上
        List<Long> outstandings = userStats.stream()
                .map(us -> longValue(us.get("outstandingOwnTotal")))
                .sorted()
                .toList();
        long threshold = 5;
        if (!outstandings.isEmpty()) {
            int index = Math.max(0, Math.min(outstandings.size() - 1, (int) Math.ceil(outstandings.size() * 0.75) - 1));
            threshold = Math.max(5, outstandings.get(index));
        }

        for (Map<String, Object> us : userStats) {
            long outstanding = longValue(us.get("outstandingOwnTotal"));
            long waiting = longValue(us.get("outstandingWaitingTotal"));
            long created = longValue(us.get("workloadCreated"));
            Object rateValue = us.get("completionRate");
            Double rate = rateValue instanceof Number number ? number.doubleValue() : null;
            String rateText = rate == null ? "本期无新增" : Math.round(rate) + "%";

            String statusKey;
            String statusLabel;
            String statusReason;
            if (created == 0 && outstanding == 0) {
                statusKey = "idle";
                statusLabel = "本期无工作量";
                statusReason = waiting > 0 ? "本期没有新增，手上没有待办；另有 " + waiting + " 项已交出，等他人处理。" : "本期没有新增，也没有在手未完成。";
            } else if (outstanding >= threshold && (rate == null || rate < 60)) {
                statusKey = "risk";
                statusLabel = "需关注";
                statusReason = "自己要做 " + outstanding + " 项（本角色阈值 " + threshold + "），本期新增完成率 " + rateText + "；另有 "
                        + waiting + " 项等他人处理。";
            } else if (outstanding >= threshold || (created >= 3 && rate != null && rate < 40)) {
                statusKey = "watch";
                statusLabel = "留意";
                statusReason = outstanding >= threshold
                        ? "自己要做 " + outstanding + " 项，已达本角色阈值 " + threshold + "，但本期消化率尚可（" + rateText + "）。"
                        : "本期新增 " + created + " 项，完成率只有 " + rateText + "。";
            } else {
                statusKey = "steady";
                statusLabel = "正常";
                statusReason = "自己要做 " + outstanding + " 项，等他人 " + waiting + " 项，本期新增完成率 " + rateText + "。";
            }
            us.put("statusKey", statusKey);
            us.put("statusLabel", statusLabel);
            us.put("statusReason", statusReason);
            us.put(
                    "statusOrder",
                    switch (statusKey) {
                        case "risk" -> 0;
                        case "watch" -> 1;
                        case "steady" -> 2;
                        default -> 3;
                    });
            us.put("attentionThreshold", threshold);
        }
    }
}
