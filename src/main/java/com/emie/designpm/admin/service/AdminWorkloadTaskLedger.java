package com.emie.designpm.admin.service;

import com.emie.designpm.admin.service.WorkloadStatusBoundary.Bucket;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 子任务工作量台账：逐件任务归户，而唔係喺 SQL 入面叠一堆 CASE。
 *
 * <p>三条界限全部喺呢度定义，方便单测钉死：
 *
 * <ul>
 *   <li><b>时间</b>：本期新增 = {@code cutoff <= created_at < endExclusive}；本期完成 =
 *       {@code cutoff <= completed_at < endExclusive}；期末在手 = 期末之前建立而期末仍未完成。
 *   <li><b>状态</b>：在手再按 {@link WorkloadStatusBoundary} 拆「自己要做」同「等他人」。
 *   <li><b>归户</b>：一件任务同一个人只计一次（企划自己派自己做嗰啲唔会重复计）；
 *       既係承接人又係发布人时，只要其中一边轮到佢做就当「自己要做」。
 * </ul>
 */
final class AdminWorkloadTaskLedger {

    /** 一行子任务；projectType 用嚟拆渠道定制／常规品。 */
    record TaskRow(
            String assigneeId,
            String publisherId,
            String status,
            LocalDateTime createdAt,
            LocalDateTime completedAt,
            String projectType) {}

    /** 单个人（或「未分配」桶）嘅统计。 */
    static final class Tally {
        long created;
        long completedFromCreated;
        long completedInRange;
        long outstandingOwn;
        long outstandingWaiting;
        long channelCreated;
        long regularCreated;
        long channelCompleted;
        long regularCompleted;
        /** 期末在手任务嘅构成：渠道定制／常规品。 */
        long outstandingChannel;

        long outstandingRegular;

        long outstandingTotal() {
            return outstandingOwn + outstandingWaiting;
        }
    }

    record Result(Map<String, Tally> byUser, Tally unassigned) {}

    private AdminWorkloadTaskLedger() {}

    static Result aggregate(List<TaskRow> rows, LocalDateTime cutoff, LocalDateTime endExclusive) {
        Map<String, Tally> byUser = new LinkedHashMap<>();
        Tally unassigned = new Tally();

        for (TaskRow row : rows) {
            if (row.createdAt() == null || !row.createdAt().isBefore(endExclusive)) continue;

            boolean createdInRange =
                    !row.createdAt().isBefore(cutoff) && row.createdAt().isBefore(endExclusive);
            boolean completedByEnd =
                    row.completedAt() != null && row.completedAt().isBefore(endExclusive);
            boolean completedInRange = completedByEnd && !row.completedAt().isBefore(cutoff);
            boolean outstanding = !completedByEnd;
            boolean channel = "channel_custom".equals(row.projectType());

            String assignee = blankToNull(row.assigneeId());
            String publisher = blankToNull(row.publisherId());

            if (assignee == null && publisher == null) {
                apply(unassigned, Bucket.OWN, createdInRange, completedInRange, completedByEnd, outstanding, channel);
                continue;
            }

            // 同一个人只计一次；两种身份都有就取「自己要做」优先
            Set<String> related = new LinkedHashSet<>();
            if (assignee != null) related.add(assignee);
            if (publisher != null) related.add(publisher);
            for (String userId : related) {
                Bucket bucket = bucketFor(userId, assignee, publisher, row.status());
                apply(
                        byUser.computeIfAbsent(userId, ignored -> new Tally()),
                        bucket,
                        createdInRange,
                        completedInRange,
                        completedByEnd,
                        outstanding,
                        channel);
            }

            // 有发布人但未指派承接人：呢啲活冇人托住，要单独睇得见
            if (assignee == null) {
                apply(unassigned, Bucket.OWN, createdInRange, completedInRange, completedByEnd, outstanding, channel);
            }
        }
        return new Result(byUser, unassigned);
    }

    private static Bucket bucketFor(String userId, String assignee, String publisher, String status) {
        boolean isAssignee = userId.equals(assignee);
        boolean isPublisher = userId.equals(publisher);
        if (isAssignee && isPublisher) {
            Bucket asAssignee = WorkloadStatusBoundary.forAssignee(status);
            Bucket asPublisher = WorkloadStatusBoundary.forPublisher(status);
            if (asAssignee == Bucket.DONE) return Bucket.DONE;
            return asAssignee == Bucket.OWN || asPublisher == Bucket.OWN ? Bucket.OWN : Bucket.WAITING;
        }
        return isAssignee ? WorkloadStatusBoundary.forAssignee(status) : WorkloadStatusBoundary.forPublisher(status);
    }

    private static void apply(
            Tally tally,
            Bucket bucket,
            boolean createdInRange,
            boolean completedInRange,
            boolean completedByEnd,
            boolean outstanding,
            boolean channel) {
        if (createdInRange) {
            tally.created++;
            if (channel) tally.channelCreated++;
            else tally.regularCreated++;
            if (completedByEnd) tally.completedFromCreated++;
        }
        if (completedInRange) {
            tally.completedInRange++;
            if (channel) tally.channelCompleted++;
            else tally.regularCompleted++;
        }
        if (outstanding) {
            if (bucket == Bucket.OWN) tally.outstandingOwn++;
            else tally.outstandingWaiting++;
            if (channel) tally.outstandingChannel++;
            else tally.outstandingRegular++;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
