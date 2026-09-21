package com.emie.designpm.admin.service;

import java.util.Set;

/**
 * 工作量口径嘅「状态界限」单一来源。
 *
 * <p>一件未完结嘅子任务，对**承接人**同**发布人**嚟讲责任唔同：设计师交咗货等验收，
 * 对设计师系「等他人」，对企划先系「自己要做」。旧版把所有未完结任务一律当成承接人
 * 嘅在手积压，令「需关注」指错人（实测 86 件未完成入面有 48 件系等对方处理）。
 *
 * <p>全部子任务状态必须喺呢度登记；新增状态而冇登记，{@code WorkloadStatusBoundaryTest}
 * 会失败，避免新状态静静鸡跌出统计。
 */
final class WorkloadStatusBoundary {

    /** 责任归属：自己要做 / 等他人 / 已完结。 */
    enum Bucket {
        OWN,
        WAITING,
        DONE
    }

    // ===== 子任务状态全集 =====
    /** 待接单：等承接人接。 */
    static final String PENDING = "pending";
    /** 设计/制作中。 */
    static final String ACCEPTED = "accepted";
    /** 被驳回：要返工。 */
    static final String REJECTED = "rejected";
    /** 已交付，等验收。 */
    static final String DELIVERED = "delivered";
    /** 已送审，等审核。 */
    static final String SUBMITTED_FOR_REVIEW = "submitted_for_review";
    /** 企划已验收，等后续评分。 */
    static final String PLANNER_APPROVED = "planner_approved";
    /** 销售已评分，等后续。 */
    static final String SALES_APPROVED = "sales_approved";
    /** 管理已评分，等后续。 */
    static final String ADMIN_APPROVED = "admin_approved";
    /** 评分齐全，真正完结（写入 completed_at）。 */
    static final String COMPLETED = "completed";

    static final Set<String> ALL = Set.of(
            PENDING,
            ACCEPTED,
            REJECTED,
            DELIVERED,
            SUBMITTED_FOR_REVIEW,
            PLANNER_APPROVED,
            SALES_APPROVED,
            ADMIN_APPROVED,
            COMPLETED);

    private WorkloadStatusBoundary() {}

    static boolean isKnown(String status) {
        return status != null && ALL.contains(status);
    }

    /** 承接人（设计师／供应链／其他被指派嘅人）视角。 */
    static Bucket forAssignee(String status) {
        if (COMPLETED.equals(status)) return Bucket.DONE;
        return switch (status == null ? "" : status) {
                // 波喺自己脚下：等接单、做紧、被驳回要返工
            case PENDING, ACCEPTED, REJECTED -> Bucket.OWN;
                // 已经交出去：等验收、等审核、等评分
            case DELIVERED, SUBMITTED_FOR_REVIEW, PLANNER_APPROVED, SALES_APPROVED, ADMIN_APPROVED -> Bucket.WAITING;
                // 未登记状态一律当「等他人」，宁可少报积压都唔好错怪人
            default -> Bucket.WAITING;
        };
    }

    /** 项目对「发起人（销售）」视角：只有草稿未提交先系自己手上嘅活。 */
    static Bucket forProjectOwner(String status) {
        if ("completed".equals(status)) return Bucket.DONE;
        if ("terminated".equals(status)) return Bucket.DONE;
        return "draft".equals(status) ? Bucket.OWN : Bucket.WAITING;
    }

    /** 项目对「产品企划」视角：等佢接单先系自己嘅活，其余喺子任务度体现。 */
    static Bucket forProjectPlanner(String status) {
        if ("completed".equals(status)) return Bucket.DONE;
        if ("terminated".equals(status)) return Bucket.DONE;
        return "pending_planner".equals(status) ? Bucket.OWN : Bucket.WAITING;
    }

    /** 发布人（派单嘅企划）视角：承接人交返嚟嗰刻先轮到佢做。 */
    static Bucket forPublisher(String status) {
        if (COMPLETED.equals(status)) return Bucket.DONE;
        return switch (status == null ? "" : status) {
                // 等自己验收／审核
            case DELIVERED, SUBMITTED_FOR_REVIEW -> Bucket.OWN;
                // 等承接人做，或者等销售／管理评分
            case PENDING, ACCEPTED, REJECTED, PLANNER_APPROVED, SALES_APPROVED, ADMIN_APPROVED -> Bucket.WAITING;
            default -> Bucket.WAITING;
        };
    }
}
