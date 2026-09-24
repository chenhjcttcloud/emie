package com.emie.designpm.admin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emie.designpm.admin.service.AdminWorkloadTaskLedger.TaskRow;
import com.emie.designpm.admin.service.WorkloadStatusBoundary.Bucket;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 工作量口径嘅界限测试：状态界限同时间界限都要钉死。
 *
 * <p>呢个页面系管理层用嚟判断「边个卡住」，数错就会错怪人，所以每条边界都要有测试守住。
 */
class WorkloadBoundaryTest {

    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 9, 1, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 10, 1, 0, 0);

    // ==================== 状态界限 ====================

    @Test
    @DisplayName("每个子任务状态都要登记，新增状态唔可以静静鸡跌出统计")
    void every_status_is_registered() {
        for (String status : WorkloadStatusBoundary.ALL) {
            assertTrue(WorkloadStatusBoundary.isKnown(status), status + " 未登记");
            assertTrue(WorkloadStatusBoundary.forAssignee(status) != null);
            assertTrue(WorkloadStatusBoundary.forPublisher(status) != null);
        }
        assertEquals(9, WorkloadStatusBoundary.ALL.size(), "状态全集有变：请同步更新界限映射同本测试");
    }

    @Test
    @DisplayName("承接人视角：等接单/做紧/被驳回先算自己要做，交咗出去就系等他人")
    void assignee_boundary() {
        assertEquals(Bucket.OWN, WorkloadStatusBoundary.forAssignee("pending"));
        assertEquals(Bucket.OWN, WorkloadStatusBoundary.forAssignee("accepted"));
        assertEquals(Bucket.OWN, WorkloadStatusBoundary.forAssignee("rejected"));
        assertEquals(Bucket.WAITING, WorkloadStatusBoundary.forAssignee("delivered"));
        assertEquals(Bucket.WAITING, WorkloadStatusBoundary.forAssignee("submitted_for_review"));
        assertEquals(Bucket.WAITING, WorkloadStatusBoundary.forAssignee("planner_approved"));
        assertEquals(Bucket.DONE, WorkloadStatusBoundary.forAssignee("completed"));
    }

    @Test
    @DisplayName("发布人视角同承接人系镜像：交付/送审轮到企划做，其余等人")
    void publisher_boundary_is_mirror() {
        assertEquals(Bucket.OWN, WorkloadStatusBoundary.forPublisher("delivered"));
        assertEquals(Bucket.OWN, WorkloadStatusBoundary.forPublisher("submitted_for_review"));
        assertEquals(Bucket.WAITING, WorkloadStatusBoundary.forPublisher("pending"));
        assertEquals(Bucket.WAITING, WorkloadStatusBoundary.forPublisher("accepted"));
        assertEquals(Bucket.WAITING, WorkloadStatusBoundary.forPublisher("planner_approved"));
        assertEquals(Bucket.DONE, WorkloadStatusBoundary.forPublisher("completed"));
    }

    @Test
    @DisplayName("已终止项目唔算积压")
    void terminated_project_is_not_backlog() {
        assertEquals(Bucket.DONE, WorkloadStatusBoundary.forProjectOwner("terminated"));
        assertEquals(Bucket.DONE, WorkloadStatusBoundary.forProjectPlanner("terminated"));
        assertEquals(Bucket.OWN, WorkloadStatusBoundary.forProjectOwner("draft"));
        assertEquals(Bucket.WAITING, WorkloadStatusBoundary.forProjectOwner("in_progress"));
        assertEquals(Bucket.OWN, WorkloadStatusBoundary.forProjectPlanner("pending_planner"));
    }

    // ==================== 时间界限 ====================

    @Test
    @DisplayName("本期新增：起点当日计入，终点当日唔计入（左闭右开）")
    void created_range_is_half_open() {
        var result = AdminWorkloadTaskLedger.aggregate(
                List.of(
                        row("d1", CUTOFF.minusSeconds(1)), // 区间前
                        row("d1", CUTOFF), // 起点：计入
                        row("d1", END.minusSeconds(1)), // 终点前一秒：计入
                        row("d1", END)), // 终点：唔计入（亦唔会落任何统计）
                CUTOFF,
                END);
        assertEquals(2, result.byUser().get("d1").created);
        // 区间前建立而仍未完成嘅，唔算本期新增，但要算期末在手
        assertEquals(3, result.byUser().get("d1").outstandingTotal());
    }

    @Test
    @DisplayName("完成时间同样左闭右开；期末之后先完成嘅，期末当日仍然算在手")
    void completion_respects_range_end() {
        var result = AdminWorkloadTaskLedger.aggregate(
                List.of(
                        new TaskRow("d1", "p1", "completed", CUTOFF.plusDays(1), CUTOFF.plusDays(2), "regular"),
                        new TaskRow("d1", "p1", "completed", CUTOFF.plusDays(1), END, "regular")),
                CUTOFF,
                END);
        var tally = result.byUser().get("d1");
        assertEquals(2, tally.created);
        assertEquals(1, tally.completedInRange, "喺终点当日完成嘅唔属于本期");
        assertEquals(1, tally.completedFromCreated);
        assertEquals(1, tally.outstandingTotal(), "期末未完成，即使之后完成咗都要算在手");
    }

    @Test
    @DisplayName("同批完成率：往期开、本期完成嘅唔可以做分子")
    void completed_in_range_is_not_cohort_numerator() {
        var result = AdminWorkloadTaskLedger.aggregate(
                List.of(new TaskRow("d1", "p1", "completed", CUTOFF.minusDays(5), CUTOFF.plusDays(1), "regular")),
                CUTOFF,
                END);
        var tally = result.byUser().get("d1");
        assertEquals(0, tally.created);
        assertEquals(0, tally.completedFromCreated, "分母唔包佢，分子都唔可以包");
        assertEquals(1, tally.completedInRange, "但「本期完成」要睇到");
    }

    // ==================== 归户界限 ====================

    @Test
    @DisplayName("交付待验收：承接人算等他人，发布人算自己要做")
    void delivered_task_splits_by_perspective() {
        var result = AdminWorkloadTaskLedger.aggregate(
                List.of(new TaskRow("designer", "planner", "delivered", CUTOFF.plusDays(1), null, "channel_custom")),
                CUTOFF,
                END);
        assertEquals(0, result.byUser().get("designer").outstandingOwn);
        assertEquals(1, result.byUser().get("designer").outstandingWaiting);
        assertEquals(1, result.byUser().get("planner").outstandingOwn);
        assertEquals(0, result.byUser().get("planner").outstandingWaiting);
    }

    @Test
    @DisplayName("企划自己派自己做：同一件任务只计一次，唔会双重计数")
    void self_assigned_task_counted_once() {
        var result = AdminWorkloadTaskLedger.aggregate(
                List.of(new TaskRow("p1", "p1", "accepted", CUTOFF.plusDays(1), null, "regular")), CUTOFF, END);
        assertEquals(1, result.byUser().size());
        var tally = result.byUser().get("p1");
        assertEquals(1, tally.created);
        assertEquals(1, tally.outstandingTotal());
        assertEquals(1, tally.outstandingOwn, "自己做紧，属于自己要做");
    }

    @Test
    @DisplayName("未指派负责人嘅任务要单独睇得见，唔可以喺统计入面消失")
    void unassigned_tasks_are_visible() {
        var result = AdminWorkloadTaskLedger.aggregate(
                List.of(
                        new TaskRow(null, "planner", "pending", CUTOFF.plusDays(1), null, "regular"),
                        new TaskRow("", null, "pending", CUTOFF.plusDays(1), null, "regular")),
                CUTOFF,
                END);
        assertEquals(2, result.unassigned().created);
        assertEquals(2, result.unassigned().outstandingTotal());
    }

    @Test
    @DisplayName("承接角色唔系设计师／供应链嘅任务照计（销售、推广、企划承接都算）")
    void non_designer_assignees_are_counted() {
        var result = AdminWorkloadTaskLedger.aggregate(
                List.of(new TaskRow("sales_1", "planner_1", "pending", CUTOFF.plusDays(1), null, "regular")),
                CUTOFF,
                END);
        assertEquals(1, result.byUser().get("sales_1").created);
        assertEquals(1, result.byUser().get("sales_1").outstandingOwn);
    }

    @Test
    @DisplayName("在手构成同「在手」系同一批：已完成嘅唔计入渠道／常规占比")
    void outstanding_mix_matches_outstanding() {
        var result = AdminWorkloadTaskLedger.aggregate(
                List.of(
                        new TaskRow("d1", "p1", "accepted", CUTOFF.plusDays(1), null, "channel_custom"),
                        new TaskRow("d1", "p1", "delivered", CUTOFF.plusDays(1), null, "regular"),
                        new TaskRow("d1", "p1", "completed", CUTOFF.plusDays(1), CUTOFF.plusDays(2), "regular")),
                CUTOFF,
                END);
        var tally = result.byUser().get("d1");
        assertEquals(2, tally.outstandingTotal());
        assertEquals(1, tally.outstandingChannel);
        assertEquals(1, tally.outstandingRegular);
    }

    private static TaskRow row(String assignee, LocalDateTime createdAt) {
        return new TaskRow(assignee, "planner", "accepted", createdAt, null, "regular");
    }
}
