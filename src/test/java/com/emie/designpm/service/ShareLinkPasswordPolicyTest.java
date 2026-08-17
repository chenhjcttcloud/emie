package com.emie.designpm.service;

import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.ShareLink;
import com.emie.designpm.entity.User;
import com.emie.designpm.repository.ProjectRepository;
import com.emie.designpm.repository.ShareLinkRepository;
import com.emie.designpm.repository.SubTaskRepository;
import com.emie.designpm.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3 加固：分享密码复杂度策略（长度 ≥6 且非纯数字）。
 */
class ShareLinkPasswordPolicyTest {

    private final ShareLinkRepository links = mock(ShareLinkRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final SubTaskRepository tasks = mock(SubTaskRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ShareLinkService service = new ShareLinkService(links, projects, tasks, users);

    private void prepareProjectTarget() {
        User admin = new User();
        admin.setUserId("admin-1");
        admin.setRole("admin");
        when(users.findByUserId("admin-1")).thenReturn(Optional.of(admin));
        Project project = new Project();
        project.setId(1L);
        project.setType("regular");
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(links.findByToken(anyString())).thenReturn(Optional.empty());
        when(links.save(any(ShareLink.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createRejectsPasswordShorterThanSixChars() {
        prepareProjectTarget();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.createShareLink("project", 1L, "admin-1", 3600L, "12345"));
        assertEquals("分享密码长度不能少于6位", e.getMessage());
    }

    @Test
    void createRejectsAllDigitPassword() {
        prepareProjectTarget();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.createShareLink("project", 1L, "admin-1", 3600L, "123456"));
        assertEquals("分享密码不能为纯数字", e.getMessage());
    }

    @Test
    void createAcceptsCompliantPassword() {
        prepareProjectTarget();
        var result = service.createShareLink("project", 1L, "admin-1", 3600L, "ab12cd");
        assertNotNull(result.get("token"));
        verify(links).save(any(ShareLink.class));
    }

    @Test
    void createWithoutPasswordStaysOpen() {
        prepareProjectTarget();
        var result = service.createShareLink("project", 1L, "admin-1", 3600L, null);
        assertNotNull(result.get("token"));
        verify(links).save(argThat(link -> link.getPassword() == null));
    }

    @Test
    void adminUpdateRejectsWeakPasswordAndClearsOnBlank() {
        ShareLink link = ShareLink.builder()
                .token("tok")
                .targetType("project")
                .targetId(1L)
                .createdBy("admin-1")
                .status("active")
                .build();
        link.setId(9L);
        when(links.findById(9L)).thenReturn(Optional.of(link));

        IllegalArgumentException shortPwd = assertThrows(IllegalArgumentException.class,
                () -> service.adminUpdateShare(9L, null, "12345"));
        assertEquals("分享密码长度不能少于6位", shortPwd.getMessage());

        IllegalArgumentException digitPwd = assertThrows(IllegalArgumentException.class,
                () -> service.adminUpdateShare(9L, null, "888888"));
        assertEquals("分享密码不能为纯数字", digitPwd.getMessage());

        service.adminUpdateShare(9L, null, "ab12cd");
        assertNotNull(link.getPassword(), "合规密码应被编码保存");

        service.adminUpdateShare(9L, null, "");
        assertNull(link.getPassword(), "空字符串表示清除密码");
    }

    @Test
    void adminUpdateAcceptsNullAsKeepPassword() {
        ShareLink link = ShareLink.builder()
                .token("tok")
                .targetType("project")
                .targetId(1L)
                .createdBy("admin-1")
                .status("active")
                .password("legacy-hash")
                .build();
        link.setId(10L);
        when(links.findById(10L)).thenReturn(Optional.of(link));

        service.adminUpdateShare(10L, null, null);
        assertEquals("legacy-hash", link.getPassword(), "null 表示不改密码");
    }
}
