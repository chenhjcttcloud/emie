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

    @Test
    void libraryCapabilityKeepsExistingRoleAndPermissionRestrictions() {
        assertFalse(data.canSearchImages(null));
        assertFalse(data.canSearchImages(session));
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class, () -> data.searchImages("KT图档", session));
        when(permissions.has(anyString(), eq("page.image_library.view"))).thenReturn(true);
        for (String role : List.of("admin", "planner", "designer")) {
            assertTrue(data.canSearchImages(new AuthSession("user", role, "员工")));
        }
        for (String role : List.of("sales", "supplychain", "promotion")) {
            var denied = new AuthSession("user", role, "员工");
            assertFalse(data.canSearchImages(denied));
            assertThrows(
                    org.springframework.web.server.ResponseStatusException.class,
                    () -> data.searchImages("KT图档", denied));
        }
        verifyNoInteractions(library, files);
    }

    @Test
    void naturalMixedChineseAndLatinQueryFindsActualKtAndHelloKittyMetadata() {
        String query = "我想要KT猫手绘类型粉色系的图档，在我的图档库里面筛选出来展示出来";
        List<String> terms = AssistantDataService.terms(query);
        assertTrue(terms.containsAll(List.of("kt", "手绘", "粉色", "hellokitty")));
        assertFalse(terms.contains("筛选"));
        assertFalse(terms.contains("类型"));
        assertFalse(terms.contains("出来"));
        assertTrue(AssistantDataService.terms("我想要图档，在我的图档库里面筛选出来展示出来").isEmpty());
        when(permissions.has("designer", "page.image_library.view")).thenReturn(true);
        var kt = libraryItem(1L, "冬日-温暖好梦（KT）", "kt.png");
        var helloKitty = libraryItem(2L, "春日包装", "hello.png");
        helloKitty.setSubOptionsJson("[\"HelloKitty\"]");
        when(library.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(kt, helloKitty));
        bindFile("kt.png", 1L);
        bindFile("hello.png", 2L);

        var results = data.searchImages(query, session);
        assertEquals(
                List.of(1L, 2L), results.stream().map(item -> item.get("id")).toList());
        assertEquals(List.of("名称/IP/标签含“kt”"), results.get(0).get("reasons"));
        assertEquals(List.of("名称/IP/标签含“hellokitty”"), results.get(1).get("reasons"));
        assertEquals(
                List.of(Map.of("storedName", "kt.png", "name", "图档文件")),
                results.get(0).get("images"));
        assertEquals(2, data.searchImages("帮我找Hello Kitty图档", session).size());
        assertEquals(2, data.searchImages("帮我找HelloKitty图档", session).size());
        assertEquals(2, data.searchImages("帮我找Hello Kitty猫图档", session).size());
        assertTrue(data.searchImages("我想要手绘类型粉色系的图档，在我的图档库里面筛选出来展示出来", session).isEmpty());
    }

    @Test
    void legacyLibraryFilesRemainVisibleButMissingAndForeignFilesDoNotConsumeResultLimit() {
        when(permissions.has("designer", "page.image_library.view")).thenReturn(true);
        var items = new ArrayList<ImageLibraryItem>();
        for (long id = 1; id <= 12; id++) {
            String storedName = "kt-" + id + ".png";
            items.add(libraryItem(id, "KT图档" + id, storedName));
            if (id >= 7) bindFile(storedName, id == 7 ? null : id);
        }
        bindFile("kt-2.png", 99L);
        when(files.findByStoredName("kt-3.png"))
                .thenReturn(Optional.of(FileRecord.builder()
                        .storedName("kt-3.png")
                        .targetType("project")
                        .targetId(3L)
                        .build()));
        when(files.findByStoredName("kt-4.png"))
                .thenReturn(
                        Optional.of(FileRecord.builder().storedName("kt-4.png").build()));
        when(library.findAllByOrderByCreatedAtDesc()).thenReturn(items);

        var results = data.searchImages("KT图档", session);
        assertEquals(
                List.of(7L, 8L, 9L, 10L, 11L, 12L),
                results.stream().map(item -> item.get("id")).toList());
        assertEquals(
                List.of(Map.of("storedName", "kt-7.png", "name", "图档文件")),
                results.get(0).get("images"));
        for (var result : results) assertFalse(((List<?>) result.get("images")).isEmpty());
    }

    private ImageLibraryItem libraryItem(long id, String name, String storedName) {
        ImageLibraryItem item = new ImageLibraryItem();
        item.setId(id);
        item.setName(name);
        item.setIpName("三丽鸥");
        item.setImagesJson("[{\"storedName\":\"" + storedName + "\",\"name\":\"图档文件\"}]");
        return item;
    }

    private void bindFile(String storedName, Long targetId) {
        when(files.findByStoredName(storedName))
                .thenReturn(Optional.of(FileRecord.builder()
                        .storedName(storedName)
                        .targetType("image_library")
                        .targetId(targetId)
                        .build()));
    }
}
