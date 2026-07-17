package com.emie.designpm.repository;

import com.emie.designpm.dto.ProjectListQuery;
import com.emie.designpm.entity.Project;
import org.springframework.data.domain.Page;

import java.util.List;

/** 项目列表动态查询片段：仅拼接实际使用的条件，避免空参数 OR 破坏索引计划。 */
public interface ProjectSearchRepository {
    Page<Project> findVisiblePage(ProjectListQuery query, String viewerRole, List<String> visibleUserIds);

    long countVisible(ProjectListQuery query, String viewerRole, List<String> visibleUserIds);

    List<Long> findVisibleIds(ProjectListQuery query, String viewerRole, List<String> visibleUserIds);
}
