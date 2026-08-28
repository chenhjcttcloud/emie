package com.emie.designpm.service;

import com.emie.designpm.entity.Project;
import java.util.Map;

/** 项目生命周期写操作边界。 */
public interface ProjectLifecycleCommandService {
    Project terminateProject(Long projectId, Map<String, Object> body);
    Project cancelTerminate(Long projectId, Map<String, Object> body);
    Project pauseProject(Long projectId, Map<String, Object> body);
    Project resumeProject(Long projectId, Map<String, Object> body);
    void deleteProject(Long projectId);
}
