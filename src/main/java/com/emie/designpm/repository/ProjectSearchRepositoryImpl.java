package com.emie.designpm.repository;

import com.emie.designpm.dto.ProjectListQuery;
import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.ProductCategory;
import com.emie.designpm.entity.SubTask;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.util.ArrayList;
import java.util.List;

/** Criteria API 实现，确保内容查询和 count 查询使用相同的权限与筛选条件。 */
public class ProjectSearchRepositoryImpl implements ProjectSearchRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<Project> findVisiblePage(ProjectListQuery query, String viewerRole, List<String> visibleUserIds) {
        if (!"admin".equals(viewerRole) && (visibleUserIds == null || visibleUserIds.isEmpty())) {
            return Page.empty(query.pageable());
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Project> contentQuery = cb.createQuery(Project.class);
        Root<Project> project = contentQuery.from(Project.class);
        project.fetch("productCategory", JoinType.LEFT);
        contentQuery.select(project).distinct(true)
                .where(predicates(cb, project, query, viewerRole, visibleUserIds))
                .orderBy(cb.desc(project.get("createdAt")), cb.desc(project.get("id")));
        TypedQuery<Project> typedQuery = entityManager.createQuery(contentQuery);
        typedQuery.setFirstResult((int) query.pageable().getOffset());
        typedQuery.setMaxResults(query.pageable().getPageSize());
        List<Project> content = typedQuery.getResultList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Project> countProject = countQuery.from(Project.class);
        countQuery.select(cb.countDistinct(countProject.get("id")))
                .where(predicates(cb, countProject, query, viewerRole, visibleUserIds));
        long total = entityManager.createQuery(countQuery).getSingleResult();
        return new PageImpl<>(content, query.pageable(), total);
    }

    @Override
    public long countVisible(ProjectListQuery query, String viewerRole, List<String> visibleUserIds) {
        if (!"admin".equals(viewerRole) && (visibleUserIds == null || visibleUserIds.isEmpty())) return 0L;
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Project> project = countQuery.from(Project.class);
        countQuery.select(cb.countDistinct(project.get("id")))
                .where(predicates(cb, project, query, viewerRole, visibleUserIds));
        return entityManager.createQuery(countQuery).getSingleResult();
    }

    @Override
    public List<Long> findVisibleIds(ProjectListQuery query, String viewerRole, List<String> visibleUserIds) {
        if (!"admin".equals(viewerRole) && (visibleUserIds == null || visibleUserIds.isEmpty())) return List.of();
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> idsQuery = cb.createQuery(Long.class);
        Root<Project> project = idsQuery.from(Project.class);
        idsQuery.select(project.get("id")).distinct(true)
                .where(predicates(cb, project, query, viewerRole, visibleUserIds));
        return entityManager.createQuery(idsQuery).getResultList();
    }

    private Predicate[] predicates(CriteriaBuilder cb, Root<Project> project, ProjectListQuery query,
                                   String viewerRole, List<String> visibleUserIds) {
        List<Predicate> predicates = new ArrayList<>();
        addAccessPredicate(cb, project, predicates, query, viewerRole, visibleUserIds);

        if (query.type() != null) predicates.add(cb.equal(project.get("type"), query.type()));
        if (query.status() != null) {
            if ("in_progress".equals(query.status())) {
                predicates.add(project.get("status").in(List.of("in_progress", "planner_accepted")));
            } else {
                predicates.add(cb.equal(project.get("status"), query.status()));
            }
        }
        if (query.category() != null) {
            Join<Project, ProductCategory> category = project.join("productCategory", JoinType.INNER);
            predicates.add(cb.equal(category.get("name"), query.category()));
        }
        if (query.market() != null) {
            predicates.add(cb.like(cb.lower(project.get("targetMarket")), contains(query.market())));
        }
        if (query.deadlineStart() != null) predicates.add(cb.greaterThanOrEqualTo(project.get("deadline"), query.deadlineStart()));
        if (query.deadlineEnd() != null) predicates.add(cb.lessThanOrEqualTo(project.get("deadline"), query.deadlineEnd()));
        if (query.keyword() != null) addKeywordPredicate(cb, project, predicates, query.keyword());
        if (query.ownerRole() != null && query.ownerId() != null) {
            predicates.add(cb.equal("sales".equals(query.ownerRole())
                    ? project.get("salesId") : project.get("plannerId"), query.ownerId()));
        }
        return predicates.toArray(Predicate[]::new);
    }

    private void addAccessPredicate(CriteriaBuilder cb, Root<Project> project, List<Predicate> predicates,
                                    ProjectListQuery query, String viewerRole, List<String> userIds) {
        if ("admin".equals(viewerRole)) return;
        switch (viewerRole) {
            case "sales" -> predicates.add(project.get("salesId").in(userIds));
            case "planner" -> predicates.add(cb.or(
                    project.get("plannerId").in(userIds),
                    cb.and(cb.equal(project.get("type"), "channel_custom"),
                            cb.equal(project.get("status"), "pending_planner"),
                            cb.or(cb.isNull(project.get("plannerId")), cb.equal(project.get("plannerId"), "")))));
            case "designer", "supplychain" -> addAssigneePredicate(cb, project, predicates, query, viewerRole, userIds);
            default -> predicates.add(cb.disjunction());
        }
    }

    private void addAssigneePredicate(CriteriaBuilder cb, Root<Project> project, List<Predicate> predicates,
                                      ProjectListQuery query, String role, List<String> userIds) {
        Join<Project, SubTask> task = project.join("tasks", JoinType.INNER);
        Predicate rolePredicate = "designer".equals(role)
                ? cb.or(cb.equal(task.get("assigneeRole"), role), cb.isNull(task.get("assigneeRole")), cb.equal(task.get("assigneeRole"), ""))
                : cb.equal(task.get("assigneeRole"), role);
        Predicate assignment = query.participating()
                ? cb.and(task.get("designerId").in(userIds), cb.notEqual(task.get("status"), "pending"))
                : cb.or(task.get("designerId").in(userIds),
                        cb.and(cb.or(cb.isNull(task.get("designerId")), cb.equal(task.get("designerId"), "")),
                                cb.equal(task.get("status"), "pending")));
        predicates.add(cb.and(rolePredicate, assignment));
    }

    private void addKeywordPredicate(CriteriaBuilder cb, Root<Project> project, List<Predicate> predicates, String keyword) {
        String like = contains(keyword);
        List<Predicate> fields = new ArrayList<>(List.of(
                cb.like(cb.lower(project.get("projectCode")), like),
                cb.like(cb.lower(project.get("productName")), like),
                cb.like(cb.lower(project.get("description")), like),
                cb.like(cb.lower(project.get("productRequirements")), like),
                cb.like(cb.lower(project.get("salesName")), like),
                cb.like(cb.lower(project.get("plannerName")), like),
                cb.like(cb.lower(project.get("targetMarket")), like),
                cb.like(cb.lower(project.get("priceRange")), like),
                cb.like(cb.lower(project.get("ipName")), like)));
        try {
            fields.add(cb.equal(project.get("id"), Long.parseLong(keyword)));
        } catch (NumberFormatException ignored) {
            // 非数字关键词无需按主键匹配。
        }
        predicates.add(cb.or(fields.toArray(Predicate[]::new)));
    }

    private String contains(String text) {
        return "%" + text.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
