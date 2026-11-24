package com.emie.designpm.project.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.emie.designpm.admin.repository.ActivityLogRepository;
import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.dto.ProjectDetailDTO;
import com.emie.designpm.dto.TaskDetailDTO;
import com.emie.designpm.entity.FileRecord;
import com.emie.designpm.entity.Project;
import com.emie.designpm.file.repository.FileRecordRepository;
import com.emie.designpm.project.repository.SubTaskRepository;
import com.emie.designpm.project.service.ProjectAccessService;
import com.emie.designpm.project.service.ProjectLifecycleCommandService;
import com.emie.designpm.project.service.ProjectService;
import com.emie.designpm.project.service.ProjectTaskFileOverview;
import com.emie.designpm.project.service.ProjectViewSupport;
import com.emie.designpm.project.service.ProjectWorkflowService;
import com.emie.designpm.project.service.SubTaskCommandService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest(
        properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"},
        showSql = false)
@ContextConfiguration(classes = ProjectTaskFileOverviewTest.Persistence.class)
class ProjectTaskFileOverviewTest {
    @Configuration
    @EnableJpaRepositories(basePackageClasses = FileRecordRepository.class)
    @EntityScan(basePackageClasses = FileRecord.class)
    static class Persistence {}

    @Autowired
    private FileRecordRepository files;

    @Test
    void authorizedProjectShowsBoundUploadsThatAreNoLongerInCurrentTaskJson() {
        files.saveAll(List.of(
                file("earlier-reference.pdf", "sub_task", 21L, "planner-1"),
                file("designer-upload.pdf", "sub_task", 21L, "designer-1"),
                file("unknown-upload.pdf", "sub_task", 21L, null),
                file("other-task.pdf", "sub_task", 99L, null),
                file("library.pdf", "image_library", 21L, null),
                file("project.pdf", "project", 21L, null),
                file("unbound.pdf", null, null, null)));
        ProjectService projects = mock(ProjectService.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        ProjectViewSupport view = mock(ProjectViewSupport.class);
        ProjectController controller = new ProjectController(
                projects,
                mock(ActivityLogRepository.class),
                mock(SubTaskRepository.class),
                access,
                mock(ProjectWorkflowService.class),
                null,
                mock(SubTaskCommandService.class),
                mock(ProjectLifecycleCommandService.class),
                view,
                new ProjectRequestSupport(null));
        ReflectionTestUtils.setField(controller, "taskFileOverview", new ProjectTaskFileOverview(files));

        Project project = new Project();
        project.setId(9L);
        when(projects.getProjectById(9L)).thenReturn(Optional.of(project));
        var request = new MockHttpServletRequest();
        var viewer = new AuthSession("designer-1", "designer", "测试设计师");
        request.setAttribute("authSession", viewer);
        var forbidden = controller.getProjectDetail(9L, request);
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
        assertNull(forbidden.getBody());
        verifyNoInteractions(view);

        TaskDetailDTO withUpload = new TaskDetailDTO();
        withUpload.setId(21L);
        withUpload.setAttachmentsJson("[]");
        TaskDetailDTO withoutUpload = new TaskDetailDTO();
        withoutUpload.setId(22L);
        ProjectDetailDTO detail = new ProjectDetailDTO();
        detail.setPlannerId("planner-1");
        detail.setTasks(List.of(withUpload, withoutUpload));
        when(access.canView(project, viewer)).thenReturn(true);
        when(view.toDetailWithLogs(project, List.of())).thenReturn(detail);

        var allowed = controller.getProjectDetail(9L, request);
        assertEquals(HttpStatus.OK, allowed.getStatusCode());
        var uploads = allowed.getBody().getTasks().getFirst().getUploadedFiles();
        assertEquals(3, uploads.size());
        assertEquals(
                Set.of(
                        new TaskDetailDTO.UploadedFile(
                                "earlier-reference.pdf", "原始任务资料.pdf", 42L, true, "2026-11-24T10:00"),
                        new TaskDetailDTO.UploadedFile(
                                "designer-upload.pdf", "原始任务资料.pdf", 42L, false, "2026-11-24T10:00"),
                        new TaskDetailDTO.UploadedFile(
                                "unknown-upload.pdf", "原始任务资料.pdf", 42L, false, "2026-11-24T10:00")),
                Set.copyOf(uploads));
        assertEquals(List.of(), allowed.getBody().getTasks().getLast().getUploadedFiles());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(allowed.getBody());
        assertEquals(
                "2026-11-24T10:00",
                json.get("tasks")
                        .get(0)
                        .get("uploadedFiles")
                        .get(0)
                        .get("createdAt")
                        .asText());
    }

    @Test
    void repositoryReturnsRealUploadTimesNewestFirstAndKeepsLegacyDtoConstructionCompatible() {
        var earlier = file("earlier.pdf", "sub_task", 21L, "designer-1");
        earlier.setCreatedAt(LocalDateTime.of(2026, 11, 22, 9, 0));
        var latest = file("latest.pdf", "sub_task", 21L, "planner-1");
        latest.setCreatedAt(LocalDateTime.of(2026, 11, 24, 11, 30));
        var middle = file("middle.pdf", "sub_task", 21L, "designer-1");
        middle.setCreatedAt(LocalDateTime.of(2026, 11, 23, 16, 45));
        files.saveAllAndFlush(List.of(latest, earlier, middle));

        var uploads = files.findSubTaskUploads(List.of(21L));

        assertEquals(
                List.of("latest.pdf", "middle.pdf", "earlier.pdf"),
                uploads.stream()
                        .map(FileRecordRepository.SubTaskUploadProjection::getStoredName)
                        .toList());
        assertEquals(
                List.of(latest.getCreatedAt(), middle.getCreatedAt(), earlier.getCreatedAt()),
                uploads.stream()
                        .map(FileRecordRepository.SubTaskUploadProjection::getCreatedAt)
                        .toList());
        assertNull(new TaskDetailDTO.UploadedFile("legacy.pdf", "历史附件.pdf", 42L, false).createdAt());
    }

    @Test
    void projectMetadataContainsOnlyCurrentStableReferencesBoundToThatProjectEvenWithoutTasks() {
        files.saveAllAndFlush(List.of(
                file("current.png", "project", 9L, "planner-1"),
                file("url-only.pdf", "project", 9L, "planner-1"),
                file("orphan.pdf", "project", 9L, "planner-1"),
                file("other-project.pdf", "project", 10L, "planner-1"),
                file("task-file.pdf", "sub_task", 9L, "planner-1"),
                file("unbound.pdf", null, null, "planner-1")));
        ProjectDetailDTO detail = new ProjectDetailDTO();
        detail.setId(9L);
        detail.setTasks(List.of());
        detail.setReferenceImagesJson("[{\"storedName\":\"current.png\"}]");
        detail.setAttachmentsJson(
                """
                [{"url":"/api/files/download/url%2Donly.pdf?download=true"},
                 {"storedName":"other-project.pdf"},{"storedName":"task-file.pdf"},{"storedName":"unbound.pdf"},
                 {"name":"orphan.pdf","url":"/api/files/download-by-original?name=orphan.pdf"}]
                """);

        new ProjectTaskFileOverview(files).addUploads(detail);

        assertEquals(
                Map.of("current.png", "2026-11-24T10:00", "url-only.pdf", "2026-11-24T10:00"),
                detail.getProjectFileUploadTimes());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(detail);
        assertEquals(2, json.get("projectFileUploadTimes").size());
        assertEquals(
                "2026-11-24T10:00",
                json.get("projectFileUploadTimes").get("url-only.pdf").asText());
        assertEquals(List.of(), detail.getTasks());
    }

    @Test
    void malformedProjectReferenceListDoesNotHideValidAttachmentMetadata() {
        files.saveAndFlush(file("valid.pdf", "project", 9L, "planner-1"));
        ProjectDetailDTO detail = new ProjectDetailDTO();
        detail.setId(9L);
        detail.setTasks(List.of());
        detail.setReferenceImagesJson("[invalid json");
        detail.setAttachmentsJson("[{\"storedName\":\"valid.pdf\"}]");

        new ProjectTaskFileOverview(files).addUploads(detail);

        assertEquals(Map.of("valid.pdf", "2026-11-24T10:00"), detail.getProjectFileUploadTimes());
    }

    @Test
    void projectMetadataSkipsQueriesWithoutAValidProjectIdOrCurrentStableReferences() {
        FileRecordRepository repository = mock(FileRecordRepository.class);
        ProjectTaskFileOverview overview = new ProjectTaskFileOverview(repository);
        ProjectDetailDTO detail = new ProjectDetailDTO();
        detail.setTasks(List.of());
        assertEquals(Map.of(), detail.getProjectFileUploadTimes());
        detail.setReferenceImagesJson("[{\"storedName\":\"file.png\"}]");
        overview.addUploads(detail);
        detail.setId(0L);
        overview.addUploads(detail);
        detail.setId(9L);
        detail.setReferenceImagesJson("[]");
        detail.setAttachmentsJson("[{\"url\":\"/api/files/download-by-original?name=file.pdf\"}]");
        overview.addUploads(detail);

        assertEquals(Map.of(), detail.getProjectFileUploadTimes());
        verifyNoInteractions(repository);
    }

    private FileRecord file(String storedName, String targetType, Long targetId, String ownerUserId) {
        return FileRecord.builder()
                .storedName(storedName)
                .originalName("原始任务资料.pdf")
                .fileSize(42L)
                .targetType(targetType)
                .targetId(targetId)
                .ownerUserId(ownerUserId)
                .createdAt(LocalDateTime.of(2026, 11, 24, 10, 0))
                .build();
    }
}
