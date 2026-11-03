package com.emie.designpm.recommendation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.admin.repository.ActivityLogRepository;
import com.emie.designpm.admin.service.PermissionService;
import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.entity.*;
import com.emie.designpm.file.repository.FileRecordRepository;
import com.emie.designpm.imagelibrary.repository.ImageLibraryItemRepository;
import com.emie.designpm.project.repository.SubTaskRepository;
import com.emie.designpm.project.service.ProjectAccessService;
import com.emie.designpm.recommendation.analysis.AnalysisView;
import com.emie.designpm.recommendation.dto.ImageRecommendationItem;
import com.emie.designpm.recommendation.repository.TaskImageLibraryLinkRepository;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ImageRecommendationServiceTest {
    private final ImageLibraryItemRepository library = mock(ImageLibraryItemRepository.class);
    private final FileRecordRepository files = mock(FileRecordRepository.class);
    private final SubTaskRepository tasks = mock(SubTaskRepository.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final TaskImageLibraryLinkRepository links = mock(TaskImageLibraryLinkRepository.class);
    private final ActivityLogRepository logs = mock(ActivityLogRepository.class);
    private final VisualAnalysisService analysis = mock(VisualAnalysisService.class);
    private final ImageRecommendationService service = new ImageRecommendationService(
            library, files, tasks, access, permissions, links, logs, analysis, new ImageRecommendationMatcher());
    private final AuthSession designer = new AuthSession("designer-1", "designer", "设计师");
    private final Map<String, AnalysisView> profiles = new HashMap<>();
    private SubTask task;
    private ImageLibraryItem item;
    private FileRecord file;

    @BeforeEach
    void setUp() {
        Project project = new Project();
        project.setId(11L);
        project.setIpName("小黄人");
        project.setIpSubOptions("[\"太空系列\"]");
        project.setPlannerId("planner-1");
        project.setStatus("in_progress");
        task = new SubTask();
        task.setId(22L);
        task.setProject(project);
        task.setName("设计手机挂链");
        task.setDesignerId("designer-1");
        task.setStatus("accepted");
        task.setReferenceImagesJson("[]");
        when(tasks.findByIdWithProject(22L)).thenReturn(Optional.of(task));
        when(tasks.findByIdForUpdate(22L)).thenReturn(Optional.of(task));
        when(access.canView(eq(project), any())).thenReturn(true);
        when(permissions.has(anyString(), eq("page.image_library.view"))).thenReturn(true);
        item = libraryItem(1L, "小黄人", "太空系列");
        file = libraryFile(item);
        when(library.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(item));
        when(library.findById(1L)).thenReturn(Optional.of(item));
        when(files.findByTargetTypeOrderByCreatedAtDesc("image_library")).thenReturn(List.of(file));
        when(files.findByTargetTypeAndTargetId("image_library", 1L)).thenReturn(List.of(file));
        when(analysis.peekAll(anyCollection())).thenAnswer(invocation -> {
            Map<String, AnalysisView> result = new HashMap<>();
            for (String name : (Collection<String>) invocation.getArgument(0))
                result.put(name, profiles.getOrDefault(name, new AnalysisView("UNCONFIGURED", null, "未配置")));
            return result;
        });
        when(analysis.profile(anyString()))
                .thenAnswer(invocation -> profiles.getOrDefault(
                        invocation.getArgument(0), new AnalysisView("UNCONFIGURED", null, "未配置")));
    }

    @Test
    void deniesAnonymousUnrelatedRolesDisabledLibraryPermissionAndInvisibleTaskBeforeLoadingAssets() {
        assertStatus(HttpStatus.UNAUTHORIZED, () -> get(null));
        assertStatus(HttpStatus.FORBIDDEN, () -> get(new AuthSession("sales-1", "sales", "销售")));
        when(permissions.has("designer", "page.image_library.view")).thenReturn(false);
        assertStatus(HttpStatus.FORBIDDEN, () -> get(designer));
        when(permissions.has("designer", "page.image_library.view")).thenReturn(true);
        when(access.canView(task.getProject(), designer)).thenReturn(false);
        assertStatus(HttpStatus.FORBIDDEN, () -> get(designer));
        verifyNoInteractions(library, analysis);
    }

    @Test
    void taskCannotBeRequestedUnderAnotherProject() {
        assertStatus(HttpStatus.NOT_FOUND, () -> service.recommendations(99L, 22L, 8, false, designer));
        verifyNoInteractions(library, analysis);
    }

    @Test
    void referenceMetadataRemainsUsableWithoutStartingImageAnalysis() {
        List<Map<String, String>> refs = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            String name = "reference-" + i + ".png";
            refs.add(Map.of("storedName", name));
            FileRecord reference = FileRecord.builder()
                    .storedName(name)
                    .targetType("sub_task")
                    .targetId(task.getId())
                    .build();
            when(files.findByStoredName(name)).thenReturn(Optional.of(reference));
        }
        try {
            task.setReferenceImagesJson(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(refs));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        var metadataService = new ImageRecommendationService(
                library,
                files,
                tasks,
                access,
                permissions,
                links,
                logs,
                new VisualAnalysisService(),
                new ImageRecommendationMatcher());
        var response = metadataService.recommendations(11L, 22L, 8, false, designer);
        assertEquals(false, response.get("aiConfigured"));
        assertEquals(false, response.get("analysisPending"));
        assertEquals("METADATA", response.get("status"));
        assertFalse(items(response).isEmpty());
        assertTrue(items(response).stream().allMatch(item -> "METADATA".equals(item.analysisStatus())));
    }

    @Test
    void returnsRealCanonicalFilesAndMetadataFallbackWithoutInventedVisualReasons() {
        Map<String, Object> response = get(designer);
        var ranked = items(response);
        assertEquals(1, ranked.size());
        assertEquals("METADATA", response.get("status"));
        assertEquals(false, response.get("aiConfigured"));
        assertEquals(
                "/api/files/download/image-1.png",
                ranked.getFirst().images().getFirst().get("url"));
        assertTrue(ranked.getFirst().reasons().stream().noneMatch(r -> r.contains("画面")));
    }

    @Test
    void capsResultsAtEightAndCanReuseTheSameCacheForASmallerLimit() {
        List<ImageLibraryItem> many = java.util.stream.LongStream.rangeClosed(1, 12)
                .mapToObj(id -> libraryItem(id, "小黄人", "太空系列"))
                .toList();
        when(library.findAllByOrderByCreatedAtDesc()).thenReturn(many);
        when(files.findByTargetTypeOrderByCreatedAtDesc("image_library"))
                .thenReturn(many.stream()
                        .map(ImageRecommendationServiceTest::libraryFile)
                        .toList());
        assertEquals(
                8,
                items(service.recommendations(11L, 22L, 100, false, designer)).size());
        Map<String, Object> smaller = service.recommendations(11L, 22L, 3, false, designer);
        assertEquals(3, items(smaller).size());
        assertEquals(true, smaller.get("cached"));
    }

    @Test
    void forgedLibraryImagePointingAtPrivateProjectFileIsExcludedAndNotAnalyzed() {
        file.setTargetId(99L);
        assertTrue(items(get(designer)).isEmpty());
        verify(analysis, never()).profile(anyString());
    }

    @Test
    void cachesByRelevantContentAndInvalidatesForRequirementAndLibraryEdits() {
        assertEquals(false, get(designer).get("cached"));
        assertEquals(true, get(designer).get("cached"));
        task.setUpdatedAt(java.time.LocalDateTime.now());
        assertEquals(true, get(designer).get("cached"));
        task.setDetails("更换为太空主题");
        assertEquals(false, get(designer).get("cached"));
        item.setNotes("新增太空主题图案");
        assertEquals(false, get(designer).get("cached"));
    }

    @Test
    void refreshOnlyInvalidatesRankingDoesNotForceAnalysisCacheReset() {
        get(designer);
        assertEquals(false, service.recommendations(11L, 22L, 8, true, designer).get("cached"));
        assertEquals(true, get(designer).get("cached"));
    }

    @Test
    void savesAnIndependentAuditedAssociationWithoutChangingReferenceOrDeliveryEvidence() {
        task.setAttachmentsJson("[{\"name\":\"existing.zip\"}]");
        service.associate(11L, 22L, 1L, designer);
        ArgumentCaptor<TaskImageLibraryLink> captured = ArgumentCaptor.forClass(TaskImageLibraryLink.class);
        verify(links).saveAndFlush(captured.capture());
        assertEquals(22L, captured.getValue().getTaskId());
        assertEquals(1L, captured.getValue().getLibraryItemId());
        assertEquals("designer-1", captured.getValue().getActorUserId());
        assertEquals("[]", task.getReferenceImagesJson());
        assertEquals("[{\"name\":\"existing.zip\"}]", task.getAttachmentsJson());
        verify(logs).save(any(ActivityLog.class));
        verify(tasks, never()).save(any());
        verify(tasks).findByIdForUpdate(22L);
    }

    @Test
    void associationIsIdempotentAndChecksSixItemLimitWhileTaskLockIsHeld() {
        when(links.existsByTaskIdAndLibraryItemId(22L, 1L)).thenReturn(true);
        when(links.countByTaskId(22L)).thenReturn(6L);
        service.associate(11L, 22L, 1L, designer);
        verify(links, never()).saveAndFlush(any());
        verifyNoInteractions(logs);
        when(links.existsByTaskIdAndLibraryItemId(22L, 1L)).thenReturn(false);
        assertStatus(HttpStatus.CONFLICT, () -> service.associate(11L, 22L, 1L, designer));
    }

    @Test
    void unrelatedViewerAndFinishedTaskCannotBeChanged() {
        assertStatus(
                HttpStatus.FORBIDDEN,
                () -> service.associate(11L, 22L, 1L, new AuthSession("designer-2", "designer", "旁观者")));
        task.setStatus("completed");
        assertStatus(
                HttpStatus.FORBIDDEN,
                () -> service.associate(11L, 22L, 1L, new AuthSession("admin-1", "admin", "管理员")));
        verify(links, never()).saveAndFlush(any());
    }

    @Test
    void linkedAssetRemainsVisibleEvenWhenRequirementsNoLongerMatch() {
        task.getProject().setIpName("原创");
        TaskImageLibraryLink link = new TaskImageLibraryLink();
        link.setLibraryItemId(1L);
        when(links.findByTaskIdOrderByCreatedAtAsc(22L)).thenReturn(List.of(link));
        Map<String, Object> response = get(designer);
        assertTrue(items(response).isEmpty());
        assertEquals(List.of(1L), response.get("associatedLibraryItemIds"));
        assertEquals(1, ((List<?>) response.get("linkedItems")).size());
    }

    @Test
    void taskReferenceFromAnotherPrivateProjectIsNeverSentToModel() {
        task.setReferenceImagesJson("[{\"storedName\":\"private.png\"}]");
        FileRecord privateFile = new FileRecord();
        privateFile.setStoredName("private.png");
        privateFile.setTargetType("project");
        privateFile.setTargetId(999L);
        when(files.findByStoredName("private.png")).thenReturn(Optional.of(privateFile));
        get(designer);
        verify(analysis, never()).profile("private.png");
    }

    private Map<String, Object> get(AuthSession session) {
        return service.recommendations(11L, 22L, 8, false, session);
    }

    @SuppressWarnings("unchecked")
    private static List<ImageRecommendationItem> items(Map<String, Object> response) {
        return (List<ImageRecommendationItem>) response.get("items");
    }

    private static void assertStatus(HttpStatus expected, Runnable action) {
        assertEquals(
                expected,
                assertThrows(ResponseStatusException.class, action::run).getStatusCode());
    }

    private static ImageLibraryItem libraryItem(Long id, String ip, String series) {
        ImageLibraryItem item = new ImageLibraryItem();
        item.setId(id);
        item.setName("图档" + id);
        item.setIpName(ip);
        item.setSubOptionsJson("[\"" + series + "\"]");
        item.setImagesJson("[{\"storedName\":\"image-" + id + ".png\",\"url\":\"https://untrusted.invalid\"}]");
        return item;
    }

    private static FileRecord libraryFile(ImageLibraryItem item) {
        FileRecord record = new FileRecord();
        record.setStoredName("image-" + item.getId() + ".png");
        record.setOriginalName("产品效果图.png");
        record.setFileSize(200L);
        record.setTargetType("image_library");
        record.setTargetId(item.getId());
        return record;
    }
}
