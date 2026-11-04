package com.emie.designpm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.emie.designpm.admin.service.PermissionService;
import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.designrequirement.repository.DesignRequirementRepository;
import com.emie.designpm.entity.FileRecord;
import com.emie.designpm.entity.Project;
import com.emie.designpm.file.controller.FileController;
import com.emie.designpm.file.repository.FileRecordRepository;
import com.emie.designpm.file.service.FileArchiveService;
import com.emie.designpm.file.service.FilePreviewService;
import com.emie.designpm.file.service.FileThumbnailService;
import com.emie.designpm.materialmarket.repository.MaterialMarketItemRepository;
import com.emie.designpm.project.repository.ProjectRepository;
import com.emie.designpm.project.repository.SubTaskRepository;
import com.emie.designpm.project.service.ProjectAccessService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

class FileAccessRegressionTest {

    @Test
    void salesFileLookupIsRestrictedToTheCurrentSalesUser() {
        String storedName = "another-sales-attachment.pdf";
        FileRecordRepository records = mock(FileRecordRepository.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        FileController controller = fileController(mock(FileArchiveService.class), records, access, tasks);

        FileRecord record = FileRecord.builder()
                .storedName(storedName)
                .originalName("attachment.pdf")
                .fileSize(10L)
                .ownerUserId("sales-2")
                .storageTier("local")
                .build();

        when(records.findByStoredName(storedName)).thenReturn(Optional.of(record));
        AuthSession session = new AuthSession("sales-1", "sales", "销售");
        when(access.findVisibleProjectsWithTasks(session)).thenReturn(List.of());

        Boolean allowed =
                ReflectionTestUtils.invokeMethod(controller, "canAccessFile", session, storedName, storedName);

        assertFalse(allowed);
        verify(access).findVisibleProjectsWithTasks(session);
    }

    @Test
    void designerCanOpenLegacyUnboundFileReferencedByAccessibleProject() {
        String storedName = "legacy-reference.jpg";
        FileRecordRepository records = mock(FileRecordRepository.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        FileController controller = fileController(mock(FileArchiveService.class), records, access, tasks);

        FileRecord legacyRecord = FileRecord.builder()
                .storedName(storedName)
                .originalName("reference.jpg")
                .fileSize(10L)
                .storageTier("local")
                .build();
        Project visibleProject = new Project();
        visibleProject.setReferenceImagesJson(
                "[{\"url\":\"/api/files/download/" + storedName + "\",\"storedName\":\"" + storedName + "\"}]");

        when(records.findByStoredName(storedName)).thenReturn(Optional.of(legacyRecord));
        AuthSession session = new AuthSession("designer-1", "designer", "设计师");
        when(access.findVisibleProjectsWithTasks(session)).thenReturn(List.of(visibleProject));

        Boolean allowed =
                ReflectionTestUtils.invokeMethod(controller, "canAccessFile", session, storedName, storedName);

        assertTrue(allowed);
    }

    @Test
    void unboundFileNotReferencedByAccessibleProjectRemainsPrivate() {
        String storedName = "private-upload.jpg";
        FileRecordRepository records = mock(FileRecordRepository.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        FileController controller = fileController(mock(FileArchiveService.class), records, access, tasks);

        FileRecord pendingUpload = FileRecord.builder()
                .storedName(storedName)
                .originalName("private.jpg")
                .fileSize(10L)
                .ownerUserId("another-user")
                .storageTier("local")
                .build();

        when(records.findByStoredName(storedName)).thenReturn(Optional.of(pendingUpload));
        AuthSession session = new AuthSession("designer-1", "designer", "设计师");
        when(access.findVisibleProjectsWithTasks(session)).thenReturn(List.of());

        Boolean allowed =
                ReflectionTestUtils.invokeMethod(controller, "canAccessFile", session, storedName, storedName);

        assertFalse(allowed);
    }

    @Test
    void designerCanOpenFileFromAnotherDesignersTaskInSameVisibleProject() {
        String storedName = "other-designer-reference.jpg";
        FileRecordRepository records = mock(FileRecordRepository.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        FileController controller = fileController(mock(FileArchiveService.class), records, access, tasks);

        FileRecord legacyRecord = FileRecord.builder()
                .storedName(storedName)
                .originalName("reference.jpg")
                .fileSize(10L)
                .storageTier("local")
                .build();
        Project visibleProject = new Project();
        visibleProject.setId(2L);

        when(records.findByStoredName(storedName)).thenReturn(Optional.of(legacyRecord));
        AuthSession session = new AuthSession("designer-1", "designer", "设计师");
        when(access.findVisibleProjectsWithTasks(session)).thenReturn(List.of(visibleProject));
        when(tasks.countFileReferencesByProjectIds(List.of(2L), storedName)).thenReturn(1L);

        Boolean allowed =
                ReflectionTestUtils.invokeMethod(controller, "canAccessFile", session, storedName, storedName);

        assertTrue(allowed);
    }

    private static void resetUploadSlots() {
        Semaphore slots = (Semaphore) ReflectionTestUtils.getField(FileController.class, "UPLOAD_SLOTS");
        assert slots != null;
        slots.drainPermits();
        slots.release(4);
    }

    private static FileController uploadController(Path tempDir) {
        FileArchiveService archive = mock(FileArchiveService.class);
        when(archive.recordUpload(any(), any(), any(Long.class), any(), any(), any(), any()))
                .thenAnswer(invocation -> null);
        FileController controller = fileController(
                archive,
                mock(FileRecordRepository.class),
                mock(ProjectAccessService.class),
                mock(SubTaskRepository.class));
        ReflectionTestUtils.setField(controller, "uploadPath", tempDir);
        return controller;
    }

    @Test
    void emptyUploadsReleasePermitForSubsequentUploads(@TempDir Path tempDir) {
        resetUploadSlots();
        FileController controller = uploadController(tempDir);
        HttpServletRequest request = mock(HttpServletRequest.class);

        for (int i = 0; i < 4; i++) {
            ResponseEntity<Map<String, Object>> response = controller.uploadFile(
                    new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]), request);
            assertEquals(400, response.getStatusCode().value());
        }

        ResponseEntity<Map<String, Object>> response = controller.uploadFile(
                new MockMultipartFile("file", "valid.pdf", "application/pdf", "content".getBytes()), request);
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void oversizedUploadsReleasePermitForSubsequentUploads(@TempDir Path tempDir) {
        resetUploadSlots();
        FileController controller = uploadController(tempDir);
        HttpServletRequest request = mock(HttpServletRequest.class);
        MultipartFile oversized = mock(MultipartFile.class);
        when(oversized.isEmpty()).thenReturn(false);
        when(oversized.getSize()).thenReturn(200L * 1024 * 1024 + 1);
        when(oversized.getOriginalFilename()).thenReturn("oversized.pdf");

        for (int i = 0; i < 4; i++) {
            ResponseEntity<Map<String, Object>> response = controller.uploadFile(oversized, request);
            assertEquals(413, response.getStatusCode().value());
        }

        ResponseEntity<Map<String, Object>> response = controller.uploadFile(
                new MockMultipartFile("file", "valid.pdf", "application/pdf", "content".getBytes()), request);
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void invalidExtensionUploadsReleasePermitForSubsequentUploads(@TempDir Path tempDir) {
        resetUploadSlots();
        FileController controller = uploadController(tempDir);
        HttpServletRequest request = mock(HttpServletRequest.class);
        MultipartFile invalid = mock(MultipartFile.class);
        when(invalid.isEmpty()).thenReturn(false);
        when(invalid.getSize()).thenReturn(10L);
        when(invalid.getOriginalFilename()).thenReturn("malware.exe");

        for (int i = 0; i < 4; i++) {
            ResponseEntity<Map<String, Object>> response = controller.uploadFile(invalid, request);
            assertEquals(400, response.getStatusCode().value());
        }

        ResponseEntity<Map<String, Object>> response = controller.uploadFile(
                new MockMultipartFile("file", "valid.pdf", "application/pdf", "content".getBytes()), request);
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void plannerCanOpenDesignRequirementDeliveryImageUploadedByDesigner() {
        String storedName = "design-requirement-delivery.png";
        FileRecordRepository records = mock(FileRecordRepository.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        DesignRequirementRepository requirements = mock(DesignRequirementRepository.class);
        FileController controller = new FileController(
                mock(FileArchiveService.class),
                records,
                access,
                tasks,
                mock(FilePreviewService.class),
                mock(FileThumbnailService.class),
                requirements,
                null,
                null,
                mock(PermissionService.class));

        FileRecord deliveryImage = FileRecord.builder()
                .storedName(storedName)
                .originalName("蓝v联动海报.png")
                .fileSize(1_430_008L)
                .ownerUserId("designer-1")
                .storageTier("local")
                .build();

        when(records.findByStoredName(storedName)).thenReturn(Optional.of(deliveryImage));
        AuthSession session = new AuthSession("planner-1", "planner", "产品企划");
        when(access.findVisibleProjectsWithTasks(session)).thenReturn(List.of());
        when(requirements.countVisibleFileReferences("planner-1", storedName)).thenReturn(1L);

        Boolean allowed =
                ReflectionTestUtils.invokeMethod(controller, "canAccessFile", session, storedName, storedName);

        assertTrue(allowed);
    }

    @Test
    void plannerCanOpenReferenceImagePublishedToMaterialMarket() {
        String storedName = "market-reference.png";
        FileRecordRepository records = mock(FileRecordRepository.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        MaterialMarketItemRepository materials = mock(MaterialMarketItemRepository.class);
        FileController controller = new FileController(
                mock(FileArchiveService.class),
                records,
                access,
                tasks,
                mock(FilePreviewService.class),
                mock(FileThumbnailService.class),
                mock(DesignRequirementRepository.class),
                mock(ProjectRepository.class),
                materials,
                mock(PermissionService.class));

        FileRecord referenceImage = FileRecord.builder()
                .storedName(storedName)
                .originalName("参考图.png")
                .fileSize(1_430_008L)
                .ownerUserId("designer-1")
                .targetType("material_market")
                .targetId(19L)
                .storageTier("local")
                .build();

        when(records.findByStoredName(storedName)).thenReturn(Optional.of(referenceImage));
        when(materials.countFileReferencesByStoredName(storedName)).thenReturn(1L);

        AuthSession session = new AuthSession("planner-1", "planner", "产品企划");
        Boolean allowed =
                ReflectionTestUtils.invokeMethod(controller, "canAccessFile", session, storedName, storedName);

        assertTrue(allowed);
    }

    @Test
    void designerCanOpenImageLibraryFileWithoutBusinessTargetId() {
        String storedName = "library-image.png";
        FileRecordRepository records = mock(FileRecordRepository.class);
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.canViewImageLibrary("designer")).thenReturn(true);
        FileController controller = new FileController(
                mock(FileArchiveService.class),
                records,
                mock(ProjectAccessService.class),
                mock(SubTaskRepository.class),
                mock(FilePreviewService.class),
                mock(FileThumbnailService.class),
                null,
                null,
                null,
                permissions);
        FileRecord libraryImage = FileRecord.builder()
                .storedName(storedName)
                .originalName("产品图.png")
                .fileSize(1024L)
                .ownerUserId("planner-1")
                .targetType("image_library")
                .targetId(null)
                .storageTier("local")
                .build();
        when(records.findByStoredName(storedName)).thenReturn(Optional.of(libraryImage));

        Boolean allowed = ReflectionTestUtils.invokeMethod(
                controller, "canAccessFile", new AuthSession("designer-1", "designer", "设计师"), storedName, storedName);

        assertTrue(allowed, "图档库文件不应因没有业务 targetId 被拒绝");
    }

    @Test
    void revokedLibraryPermissionBlocksFileRoutesEvenForAdministrator(@TempDir Path tempDir) throws Exception {
        String storedName = "library-cover.ai";
        FileRecordRepository records = mock(FileRecordRepository.class);
        FileArchiveService archive = mock(FileArchiveService.class);
        FileThumbnailService thumbnails = mock(FileThumbnailService.class);
        FilePreviewService previews = mock(FilePreviewService.class);
        PermissionService permissions = mock(PermissionService.class);
        FileController controller = new FileController(
                archive,
                records,
                mock(ProjectAccessService.class),
                mock(SubTaskRepository.class),
                previews,
                thumbnails,
                null,
                null,
                null,
                permissions);
        FileRecord image = FileRecord.builder()
                .storedName(storedName)
                .targetType("image_library")
                .build();
        when(records.findByStoredName(storedName)).thenReturn(Optional.of(image));
        when(records.findByOriginalNameOrderByCreatedAtDesc("封面.ai")).thenReturn(List.of(image));
        when(previews.isPreviewable(storedName)).thenReturn(true);
        for (String role : List.of("admin", "designer")) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setAttribute("authSession", new AuthSession("test-user", role, "测试账号"));
            assertEquals(403, controller.library(request).getStatusCode().value());
            assertEquals(
                    403,
                    controller.thumbnail(storedName, request).getStatusCode().value());
            assertEquals(
                    403,
                    controller
                            .downloadFile(storedName, true, request)
                            .getStatusCode()
                            .value());
            assertEquals(
                    403,
                    controller
                            .previewStatus(storedName, false, request)
                            .getStatusCode()
                            .value());
            assertEquals(
                    403,
                    controller.previewFile(storedName, request).getStatusCode().value());
            assertEquals(
                    403,
                    controller.fileInfo(storedName, request).getStatusCode().value());
            assertEquals(
                    404,
                    controller
                            .downloadByOriginal("封面.ai", true, request)
                            .getStatusCode()
                            .value());
        }
        verifyNoInteractions(archive, thumbnails);

        MockHttpServletRequest authorized = new MockHttpServletRequest();
        authorized.setAttribute("authSession", new AuthSession("designer-1", "designer", "设计师"));
        when(permissions.canViewImageLibrary("designer")).thenReturn(true);
        ReflectionTestUtils.setField(controller, "uploadPath", tempDir);
        Path thumbnail = java.nio.file.Files.writeString(tempDir.resolve("cached.png"), "thumbnail");
        when(thumbnails.cached(storedName, tempDir.resolve("thumbnail-cache"))).thenReturn(Optional.of(thumbnail));
        ResponseEntity<Object> response = controller.thumbnail(storedName, authorized);
        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getHeaders().getCacheControl().contains("no-cache"));
    }

    @Test
    void projectReferenceCannotBypassLibraryPermission() {
        String storedName = "project-library-reference.png";
        FileRecordRepository records = mock(FileRecordRepository.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        FileController controller =
                fileController(mock(FileArchiveService.class), records, access, mock(SubTaskRepository.class));
        FileRecord record = FileRecord.builder()
                .storedName(storedName)
                .targetType("image_library")
                .build();
        when(records.findByStoredName(storedName)).thenReturn(Optional.of(record));
        AuthSession session = new AuthSession("designer-1", "designer", "设计师");
        Project project = new Project();
        project.setReferenceImagesJson("[{\"storedName\":\"" + storedName + "\"}]");
        when(access.findVisibleProjectsWithTasks(session)).thenReturn(List.of(project));

        Boolean allowed =
                ReflectionTestUtils.invokeMethod(controller, "canAccessFile", session, storedName, storedName);
        assertFalse(allowed);
    }

    private static FileController fileController(
            FileArchiveService archive,
            FileRecordRepository records,
            ProjectAccessService access,
            SubTaskRepository tasks) {
        return new FileController(
                archive,
                records,
                access,
                tasks,
                mock(FilePreviewService.class),
                mock(FileThumbnailService.class),
                null,
                null,
                null,
                mock(PermissionService.class));
    }

    @Test
    void genericDownloadDoesNotServeInternalCacheFiles() {
        FileRecordRepository records = mock(FileRecordRepository.class);
        FileArchiveService archive = mock(FileArchiveService.class);
        FileController controller =
                fileController(archive, records, mock(ProjectAccessService.class), mock(SubTaskRepository.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authSession", new AuthSession("admin-1", "admin", "管理员"));
        for (String directory : List.of("thumbnail-cache", "preview-cache", "restore-cache")) {
            assertEquals(
                    404,
                    controller
                            .downloadFileInSubDir(directory, "cover.png", false, request)
                            .getStatusCode()
                            .value());
            assertEquals(
                    404,
                    controller
                            .downloadFile(directory + "/cover.png", false, request)
                            .getStatusCode()
                            .value());
        }
        verifyNoInteractions(records, archive);
    }

    @Test
    void anonymousAdminManagedImageIsAllowedButOtherAdminFilesFailClosed() {
        FileController controller = fileController(
                mock(FileArchiveService.class),
                mock(FileRecordRepository.class),
                mock(ProjectAccessService.class),
                mock(SubTaskRepository.class));
        HttpServletRequest anonymous = mock(HttpServletRequest.class);

        // 白名单文件名（AdminService 生成规则）→ 匿名放行
        ResponseEntity<Object> allowed = ReflectionTestUtils.invokeMethod(
                controller, "checkDownloadAccess", "admin", "admin_logo_a1b2c3d4.png", anonymous);
        assertNull(allowed);

        // 同目录非白名单文件名 → 匿名会话缺失，fail-closed 401
        ResponseEntity<Object> rejected = ReflectionTestUtils.invokeMethod(
                controller, "checkDownloadAccess", "admin", "secret-plan.pdf", anonymous);
        assertTrue(rejected != null && rejected.getStatusCode().value() == 401);

        // 非法文件名（不符生成规则，即使扩展名是图片）同样拒绝
        ResponseEntity<Object> sneaky = ReflectionTestUtils.invokeMethod(
                controller, "checkDownloadAccess", "admin", "admin_logo_hack.png", anonymous);
        assertTrue(sneaky != null && sneaky.getStatusCode().value() == 401);
    }
}
