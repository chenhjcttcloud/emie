package com.emie.designpm.performance.service;

import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.entity.DesignRequirement;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.entity.User;
import com.emie.designpm.performance.service.DesignerMonthlyReport.Attachment;
import com.emie.designpm.performance.service.DesignerMonthlyReport.Category;
import com.emie.designpm.performance.service.DesignerMonthlyReport.Item;
import com.emie.designpm.reference.repository.DepartmentRepository;
import com.emie.designpm.util.SecurityUtil;
import com.emie.designpm.util.TextEncodingUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 从系统攞每位在职设计师单月嘅任务，组装成 {@link DesignerMonthlyReport}。
 *
 * <p>三类任务：渠道定制项目子任务、公司常规品项目子任务、设计/送审需求。
 * 已终止嘅项目／需求唔计入绩效。所有名称、日期、图片都直接读系统记录，唔做任何补填。
 */
@Service
public class DesignerMonthlyReportService {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final UserRepository users;
    private final DepartmentRepository departments;

    @PersistenceContext
    private EntityManager entityManager;

    public DesignerMonthlyReportService(UserRepository users, DepartmentRepository departments) {
        this.users = users;
        this.departments = departments;
    }

    @Transactional(readOnly = true)
    public List<DesignerMonthlyReport> buildMonth(YearMonth month) {
        LocalDateTime from = month.atDay(1).atStartOfDay();
        LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();
        LocalDateTime now = LocalDateTime.now();
        List<User> designers = users.findByRole("designer").stream()
                .filter(user -> user.getStatus() == null || "active".equalsIgnoreCase(user.getStatus()))
                .sorted(Comparator.comparing(User::getUserId))
                .toList();
        if (designers.isEmpty()) return List.of();
        List<String> ids = designers.stream().map(User::getUserId).toList();

        Map<String, List<Item>> itemsByDesigner = new java.util.HashMap<>();
        List<SubTask> tasks = loadSubTasks(ids, from, to);
        for (SubTask task : tasks) {
            itemsByDesigner
                    .computeIfAbsent(task.getDesignerId(), key -> new ArrayList<>())
                    .add(toItem(task));
        }
        List<DesignRequirement> requirements = loadRequirements(ids, from, to);
        for (DesignRequirement requirement : requirements) {
            itemsByDesigner
                    .computeIfAbsent(requirement.getDesignerId(), key -> new ArrayList<>())
                    .add(toItem(requirement));
        }

        List<DesignerMonthlyReport> reports = new ArrayList<>();
        for (User designer : designers) {
            List<Item> items = itemsByDesigner.getOrDefault(designer.getUserId(), List.of()).stream()
                    .sorted(Comparator.comparing(Item::category)
                            .thenComparing(item -> item.completedAt() == null ? item.createdAt() : item.completedAt()))
                    .toList();
            reports.add(new DesignerMonthlyReport(
                    designer.getUserId(),
                    TextEncodingUtil.repairUtf8Mojibake(designer.getName()),
                    departmentName(designer.getDepartmentId()),
                    designer.getTitle(),
                    month,
                    now,
                    items));
        }
        return reports;
    }

    /** 本月设立，或本月完成（任务详情包含往月设立、本月完成的任务）。 */
    private List<SubTask> loadSubTasks(List<String> designerIds, LocalDateTime from, LocalDateTime to) {
        return entityManager
                .createQuery(
                        "SELECT t FROM SubTask t JOIN FETCH t.project p "
                                + "WHERE t.designerId IN :ids AND (p.status IS NULL OR p.status <> 'terminated') "
                                + "AND ((t.createdAt >= :from AND t.createdAt < :to) "
                                + "OR (t.completedAt >= :from AND t.completedAt < :to))",
                        SubTask.class)
                .setParameter("ids", designerIds)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    /** 需求冇独立完成时间，同工作量页面一致：已完成需求以最后更新时间作完成时间。 */
    private List<DesignRequirement> loadRequirements(List<String> designerIds, LocalDateTime from, LocalDateTime to) {
        return entityManager
                .createQuery(
                        "SELECT d FROM DesignRequirement d WHERE d.designerId IN :ids "
                                + "AND (d.status IS NULL OR d.status <> 'terminated') "
                                + "AND ((d.createdAt >= :from AND d.createdAt < :to) "
                                + "OR (d.status = 'completed' AND d.updatedAt >= :from AND d.updatedAt < :to))",
                        DesignRequirement.class)
                .setParameter("ids", designerIds)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    private Item toItem(SubTask task) {
        String product = task.getProject().getProductName();
        String name = product == null || product.isBlank() ? task.getName() : product + "－" + task.getName();
        boolean done = "completed".equals(task.getStatus()) || "approved".equals(task.getStatus());
        List<Map<String, Object>> files = parseFiles(task.getAttachmentsJson());
        return new Item(
                "channel_custom".equals(task.getProject().getType()) ? Category.CHANNEL : Category.REGULAR,
                name,
                task.getCreatedAt(),
                parseDate(task.getPlannedDate()),
                done ? task.getCompletedAt() : null,
                images(files),
                otherFiles(files));
    }

    private Item toItem(DesignRequirement requirement) {
        List<Map<String, Object>> files = new ArrayList<>(parseFiles(requirement.getDeliveryAttachmentsJson()));
        files.addAll(parseFiles(requirement.getDeliveryReferenceImagesJson()));
        boolean done = "completed".equals(requirement.getStatus());
        return new Item(
                Category.REQUIREMENT,
                requirement.getName(),
                requirement.getCreatedAt(),
                parseDate(requirement.getDeadline()),
                done ? requirement.getUpdatedAt() : null,
                images(files),
                otherFiles(files));
    }

    private String departmentName(Long departmentId) {
        if (departmentId == null) return "";
        return departments.findById(departmentId).map(d -> d.getName()).orElse("");
    }

    static LocalDate parseDate(String value) {
        if (value == null || value.length() < 10) return null;
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static List<Map<String, Object>> parseFiles(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static List<Attachment> images(List<Map<String, Object>> files) {
        return files.stream()
                .filter(file -> SecurityUtil.isValidImageFile(fileName(file)))
                .filter(file -> file.get("url") instanceof String url && !url.isBlank())
                .map(file -> new Attachment(fileName(file), (String) file.get("url")))
                .distinct()
                .toList();
    }

    private static List<String> otherFiles(List<Map<String, Object>> files) {
        return files.stream()
                .map(DesignerMonthlyReportService::fileName)
                .filter(name -> name != null && !name.isBlank() && !SecurityUtil.isValidImageFile(name))
                .distinct()
                .toList();
    }

    private static String fileName(Map<String, Object> file) {
        Object name = file.get("name");
        return name == null ? null : String.valueOf(name);
    }
}
