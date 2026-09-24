package com.emie.designpm.performance.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

/**
 * 单个设计师单月绩效底稿。全部字段来自系统数据，指标口径集中喺呢度定义：
 *
 * <ul>
 *   <li><b>本月设立</b>：{@code created_at} 落喺当月嘅任务（「只核算单月设立的任务」）。
 *   <li><b>按时完成</b>：已完成，且完成日期唔迟过计划完成日期；冇计划日期嘅当按时，但会喺表入面标明。
 *   <li><b>设计任务完成率</b>：本月设立任务中按时完成数 ÷ 本月设立任务数，150% 封顶。
 * </ul>
 */
public record DesignerMonthlyReport(
        String userId,
        String name,
        String department,
        String title,
        YearMonth month,
        LocalDateTime generatedAt,
        List<Item> items) {

    static final double COMPLETION_RATE_CAP = 1.5;

    public enum Category {
        CHANNEL("渠道定制项目"),
        REGULAR("公司常规品项目"),
        REQUIREMENT("设计/送审需求");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Attachment(String name, String url) {}

    public record Item(
            Category category,
            String name,
            LocalDateTime createdAt,
            LocalDate plannedDate,
            LocalDateTime completedAt,
            List<Attachment> images,
            List<String> otherFiles) {

        public boolean createdIn(YearMonth month) {
            return createdAt != null && YearMonth.from(createdAt).equals(month);
        }

        public boolean completedIn(YearMonth month) {
            return completedAt != null && YearMonth.from(completedAt).equals(month);
        }

        public boolean completed() {
            return completedAt != null;
        }

        public boolean onTime() {
            return completed()
                    && (plannedDate == null || !completedAt.toLocalDate().isAfter(plannedDate));
        }

        public String statusLabel() {
            if (!completed()) return "未完成";
            if (plannedDate == null) return "已完成（无计划日期）";
            return onTime() ? "按时完成" : "逾期完成";
        }
    }

    public List<Item> createdItems() {
        return items.stream().filter(item -> item.createdIn(month)).toList();
    }

    /** 往月设立、本月完成：不计入当月新设任务的完成率。 */
    public List<Item> carriedOverCompleted() {
        return items.stream()
                .filter(item -> !item.createdIn(month) && item.completedIn(month))
                .toList();
    }

    public long createdCount() {
        return createdItems().size();
    }

    public long completedCount() {
        return createdItems().stream().filter(Item::completed).count();
    }

    public long onTimeCount() {
        return createdItems().stream().filter(Item::onTime).count();
    }

    /** 按时完成率（0–1.5）；本月冇设立任务时返回 null，由人工判断。 */
    public Double completionRate() {
        long created = createdCount();
        if (created == 0) return null;
        return Math.min((double) onTimeCount() / created, COMPLETION_RATE_CAP);
    }

    /** 设计任务完成率对应嘅考核评分（百分制，100% 完成率 = 100 分）。 */
    public Double completionScore() {
        Double rate = completionRate();
        return rate == null ? null : Math.round(rate * 1000.0) / 10.0;
    }

    public DesignerMonthlyReport {
        Objects.requireNonNull(month, "month");
        items = items == null ? List.of() : List.copyOf(items);
    }
}
