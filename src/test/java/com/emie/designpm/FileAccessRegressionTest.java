package com.emie.designpm;

import com.emie.designpm.controller.AuthController;
import com.emie.designpm.controller.FileController;
import com.emie.designpm.entity.FileRecord;
import com.emie.designpm.entity.Project;
import com.emie.designpm.repository.FileRecordRepository;
import com.emie.designpm.repository.DesignRequirementRepository;
import com.emie.designpm.repository.MaterialMarketItemRepository;
import com.emie.designpm.repository.ProjectRepository;
import com.emie.designpm.repository.SubTaskRepository;
import com.emie.designpm.service.FileArchiveService;
import com.emie.designpm.service.FilePreviewService;
import com.emie.designpm.service.ProjectAccessService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileAccessRegressionTest {

    @Test
    void salesFileLookupIsRestrictedToTheCurrentSalesUser() {
        String storedName = "another-sales-attachment.pdf";
        FileRecordRepository records = mock(FileRecordRepository.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        FileController controller = new FileController(mock(FileArchiveService.class), records, access, tasks,
                mock(FilePreviewService.class));

        FileRecord record = FileRecord.builder()
                .storedName(storedName)
                .originalName("attachment.pdf")
                .fileSize(10L)
                .ownerUserId("sales-2")
                .storageTier("local")
                .build();

        when(records.findByStoredName(storedName)).thenReturn(Optional.of(record));
        AuthController.AuthSession session = new AuthController.AuthSession("sales-1", "sales", "销售");
        when(access.findVisibleProjectsWithTasks(session)).thenReturn(List.of());

        Boolean allowed = ReflectionTestUtils.invokeMethod(controller, "canAccessFile",
                session, storedName, storedName);

        assertFalse(allowed);
        verify(access).findVisibleProjectsWithTasks(session);
    }

    @Test
    void designerCanOpenLegacyUnboundFileReferencedByAccessibleProject() {
        String storedName = "legacy-reference.jpg";
        FileRecordRepository records = mock(FileRecordRepository.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        FileController controller = new FileController(mock(FileArchiveService.class), records, access, tasks,
                mock(FilePreviewService.class));

        FileRecord legacyRecord = FileRecord.builder()
                .storedName(storedName)
                .originalName("reference.jpg")
                .fileSize(10L)
                .storageTier("local")
                .build();
        Project visibleProject = new Project();
        visibleProject.setReferenceImagesJson("[{\"url\":\"/api/files/download/" + storedName
                + "\",\"storedName\":\"" + storedName + "\"}]");

        when(records.findByStoredName(storedName)).thenReturn(Optional.of(legacyRecord));
        AuthController.AuthSession session = new AuthController.AuthSession("designer-1", "designer", "设计师");
        when(access.findVisibleProjectsWithTasks(session)).thenReturn(List.of(visibleProject));

        Boolean allowed = ReflectionTestUtils.invokeMethod(controller, "canAccessFile",
                session, storedName, storedName);

        assertTrue(allowed);
    }

    @Test
    void unboundFileNotReferencedByAccessibleProjectRemainsPrivate() {
        String storedName = "private-upload.jpg";
        FileRecordRepository records = mock(FileRecordRepository.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        FileController controller = new FileController(mock(FileArchiveService.class), records, access, tasks,
                mock(FilePreviewService.class));

        FileRecord pendingUpload = FileRecord.builder()
                .storedName(storedName)
                .originalName("private.jpg")
                .fileSize(10L)
                .ownerUserId("another-user")
                .storageTier("local")
                .build();

        when(records.findByStoredName(storedName)).thenReturn(Optional.of(pendingUpload));
        AuthController.AuthSession session = new AuthController.AuthSession("designer-1", "designer", "设计师");
        when(access.findVisibleProjectsWithTasks(session)).thenReturn(List.of());

        Boolean allowed = ReflectionTestUtils.invokeMethod(controller, "canAccessFile",
                session, storedName, storedName);

        assertFalse(allowed);
    }

    @Test
    void designerCanOpenFileFromAnotherDesignersTaskInSameVisibleProject() {
        String storedName = "other-designer-reference.jpg";
        FileRecordRepository records = mock(FileRecordRepository.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        FileController controller = new FileController(mock(FileArchiveService.class), records, access, tasks,
                mock(FilePreviewService.class));

        FileRecord legacyRecord = FileRecord.builder()
                .storedName(storedName)
                .originalName("reference.jpg")
                .fileSize(10L)
                .storageTier("local")
                .build();
        Project visibleProject = new Project();
        visibleProject.setId(2L);

        when(records.findByStoredName(storedName)).thenReturn(Optional.of(legacyRecord));
        AuthController.AuthSession session = new AuthController.AuthSession("designer-1", "designer", "设计师");
        when(access.findVisibleProjectsWithTasks(session)).thenReturn(List.of(visibleProject));
        when(tasks.countFileReferencesByProjectIds(List.of(2L), storedName)).thenReturn(1L);

        Boolean allowed = ReflectionTestUtils.invokeMethod(controller, "canAccessFile",
                session, storedName, storedName);

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
        FileController controller = new FileController(archive, mock(FileRecordRepository.class),
                mock(ProjectAccessService.class), mock(SubTaskRepository.class), mock(FilePreviewService.class));
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
        FileController controller = new FileController(mock(FileArchiveService.class), records, access, tasks,
                mock(FilePreviewService.class), mock(com.emie.designpm.service.FileThumbnailService.class),
                requirements);

        FileRecord deliveryImage = FileRecord.builder()
                .storedName(storedName)
                .originalName("蓝v联动海报.png")
                .fileSize(1_430_008L)
                .ownerUserId("designer-1")
                .storageTier("local")
                .build();

        when(records.findByStoredName(storedName)).thenReturn(Optional.of(deliveryImage));
        AuthController.AuthSession session = new AuthController.AuthSession("planner-1", "planner", "产品企划");
        when(access.findVisibleProjectsWithTasks(session)).thenReturn(List.of());
        when(requirements.countVisibleFileReferences("planner-1", storedName)).thenReturn(1L);

        Boolean allowed = ReflectionTestUtils.invokeMethod(controller, "canAccessFile",
                session, storedName, storedName);

        assertTrue(allowed);
    }

    @Test
    void plannerCanOpenReferenceImagePublishedToMaterialMarket() {
        String storedName = "market-reference.png";
        FileRecordRepository records = mock(FileRecordRepository.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        MaterialMarketItemRepository materials = mock(MaterialMarketItemRepository.class);
        FileController controller = new FileController(mock(FileArchiveService.class), records, access, tasks,
                mock(FilePreviewService.class), mock(com.emie.designpm.service.FileThumbnailService.class),
                mock(DesignRequirementRepository.class), mock(ProjectRepository.class), materials);

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

        AuthController.AuthSession session = new AuthController.AuthSession("planner-1", "planner", "产品企划");
        Boolean allowed = ReflectionTestUtils.invokeMethod(controller, "canAccessFile",
                session, storedName, storedName);

        assertTrue(allowed);
    }

    @Test
    void anonymousAdminManagedImageIsAllowedButOtherAdminFilesFailClosed() {
        FileController controller = new FileController(mock(FileArchiveService.class),
                mock(FileRecordRepository.class), mock(ProjectAccessService.class),
                mock(SubTaskRepository.class), mock(FilePreviewService.class));
        HttpServletRequest anonymous = mock(HttpServletRequest.class);

        // 白名单文件名（AdminService 生成规则）→ 匿名放行
        ResponseEntity<Object> allowed = ReflectionTestUtils.invokeMethod(controller, "checkDownloadAccess",
                "admin", "admin_logo_a1b2c3d4.png", anonymous);
        assertNull(allowed);

        // 同目录非白名单文件名 → 匿名会话缺失，fail-closed 401
        ResponseEntity<Object> rejected = ReflectionTestUtils.invokeMethod(controller, "checkDownloadAccess",
                "admin", "secret-plan.pdf", anonymous);
        assertTrue(rejected != null && rejected.getStatusCode().value() == 401);

        // 非法文件名（不符生成规则，即使扩展名是图片）同样拒绝
        ResponseEntity<Object> sneaky = ReflectionTestUtils.invokeMethod(controller, "checkDownloadAccess",
                "admin", "admin_logo_hack.png", anonymous);
        assertTrue(sneaky != null && sneaky.getStatusCode().value() == 401);
    }
}
