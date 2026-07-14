package com.emie.designpm;

import com.emie.designpm.entity.IpOption;
import com.emie.designpm.entity.Project;
import com.emie.designpm.repository.*;
import com.emie.designpm.service.*;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProjectIpConfigurationTest {

    @Test
    void configuredActiveIpIsSavedOnProject() {
        IpOptionRepository ipOptions = mock(IpOptionRepository.class);
        IpOption option = new IpOption("测试IP", 1);
        when(ipOptions.findByName("测试IP")).thenReturn(Optional.of(option));

        ProjectRepository projects = mock(ProjectRepository.class);
        when(projects.saveAndFlush(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ProjectService service = createService(projects, ipOptions);

        Project saved = service.createProject(validProjectBody("测试IP"));

        assertEquals("测试IP", saved.getIpName());
        verify(projects).saveAndFlush(saved);
    }

    @Test
    void unknownOrDisabledIpCannotBeSubmitted() {
        IpOptionRepository ipOptions = mock(IpOptionRepository.class);
        when(ipOptions.findByName("无效IP")).thenReturn(Optional.empty());
        ProjectService service = createService(mock(ProjectRepository.class), ipOptions);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.createProject(validProjectBody("无效IP")));

        assertEquals("请选择有效的IP", error.getMessage());
    }

    private ProjectService createService(ProjectRepository projects, IpOptionRepository ipOptions) {
        UserService users = mock(UserService.class);
        when(users.getUserName(any())).thenAnswer(invocation -> String.valueOf((Object) invocation.getArgument(0)));
        return new ProjectService(
                projects,
                mock(SubTaskRepository.class),
                mock(ScoringRepository.class),
                users,
                mock(ProductCategoryRepository.class),
                ipOptions,
                mock(SystemConfigRepository.class),
                mock(SyncQueueService.class),
                mock(FileArchiveService.class),
                mock(ProjectAccessService.class)
        );
    }

    private Map<String, Object> validProjectBody(String ipName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "regular");
        body.put("currentRole", "admin");
        body.put("currentUserId", "admin-test");
        body.put("currentUser", "测试管理员");
        body.put("plannerId", "planner-test");
        body.put("productName", "IP字段测试项目");
        body.put("deadline", "2026-07-31");
        body.put("productRequirements", "测试IP字段保存");
        body.put("ipName", ipName);
        return body;
    }
}
