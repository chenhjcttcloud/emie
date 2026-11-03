package com.emie.designpm.assistant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.admin.service.PermissionService;
import com.emie.designpm.assistant.repository.AssistantTaskRepository;
import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.entity.*;
import com.emie.designpm.file.repository.FileRecordRepository;
import com.emie.designpm.imagelibrary.repository.ImageLibraryItemRepository;
import com.emie.designpm.project.repository.ProjectRepository;
import com.emie.designpm.project.repository.SubTaskRepository;
import com.emie.designpm.project.service.ProjectAccessService;
import java.util.*;
import org.junit.jupiter.api.Test;

class AssistantDataServiceTest {
    private final PermissionService permissions = mock(PermissionService.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final SubTaskRepository tasks = mock(SubTaskRepository.class);
    private final AssistantTaskRepository own = mock(AssistantTaskRepository.class);
    private final ImageLibraryItemRepository library = mock(ImageLibraryItemRepository.class);
    private final FileRecordRepository files = mock(FileRecordRepository.class);
    private final AssistantDataService data =
            new AssistantDataService(permissions, access, projects, tasks, own, library, files);
    private final AuthSession session = new AuthSession("designer-one", "designer", "设计师");

    @Test
    void projectContextRequiresPermissionAndVisibility() {
        var context = new AssistantRequest.Context("orders", 10L, null);
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class, () -> data.context(context, session));
        verifyNoInteractions(projects);
        when(permissions.has("designer", "project.detail.view")).thenReturn(true);
        Project project = new Project();
        project.setId(10L);
        project.setProductName("秘密项目");
        when(projects.findById(10L)).thenReturn(Optional.of(project));
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class, () -> data.context(context, session));
        when(access.canView(project, session)).thenReturn(true);
        assertEquals("秘密项目", data.context(context, session).get("name"));
    }

    @Test
    void forgedProjectTaskPairIsRejected() {
        when(permissions.has("designer", "project.detail.view")).thenReturn(true);
        Project project = new Project();
        project.setId(12L);
        SubTask task = new SubTask();
        task.setProject(project);
        when(tasks.findByIdWithProject(20L)).thenReturn(Optional.of(task));
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> data.context(new AssistantRequest.Context("tasks", 10L, 20L), session));
        verifyNoInteractions(access);
    }

    @Test
    void attachmentsRequireUploaderOwnershipEvenForLibraryViewers() {
        when(permissions.has(anyString(), anyString())).thenReturn(true);
        FileRecord file = FileRecord.builder()
                .storedName("private.png")
                .ownerUserId("other")
                .build();
        when(files.findByStoredName("private.png")).thenReturn(Optional.of(file));
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> data.ownedAttachment("private.png", session));
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> data.searchImages("迪士尼", new AuthSession("sales-one", "sales", "销售")));
        verifyNoInteractions(library);
    }

    @Test
    void genericImageIntentDoesNotFillResultsWithUnrelatedAssets() {
        assertTrue(AssistantDataService.terms("帮我找相似图档").isEmpty());
        when(permissions.has("designer", "page.image_library.view")).thenReturn(true);
        assertTrue(data.searchImages("帮我找相似图档", session).isEmpty());
        verifyNoInteractions(library);
        ImageLibraryItem item = new ImageLibraryItem();
        item.setId(1L);
        item.setName("米奇包装");
        item.setIpName("迪士尼");
        item.setImagesJson("[{\"storedName\":\"safe.ai\",\"name\":\"图档\"}]");
        when(library.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(item));
        when(files.findByStoredName("safe.ai"))
                .thenReturn(Optional.of(FileRecord.builder()
                        .storedName("safe.ai")
                        .targetType("image_library")
                        .targetId(1L)
                        .build()));
        assertEquals(1, data.searchImages("查找迪士尼图档", session).size());
        assertTrue(data.searchImages("海洋蓝色鲸鱼", session).isEmpty());
    }
}
