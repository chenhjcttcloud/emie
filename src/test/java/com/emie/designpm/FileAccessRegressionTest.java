package com.emie.designpm;

import com.emie.designpm.controller.AuthController;
import com.emie.designpm.controller.FileController;
import com.emie.designpm.entity.FileRecord;
import com.emie.designpm.entity.Project;
import com.emie.designpm.repository.FileRecordRepository;
import com.emie.designpm.repository.ProjectRepository;
import com.emie.designpm.repository.SubTaskRepository;
import com.emie.designpm.service.FileArchiveService;
import com.emie.designpm.service.FilePreviewService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileAccessRegressionTest {

    @Test
    void designerCanOpenLegacyUnboundFileReferencedByAccessibleProject() {
        String storedName = "legacy-reference.jpg";
        FileRecordRepository records = mock(FileRecordRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        FileController controller = new FileController(mock(FileArchiveService.class), records, projects, tasks,
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
        when(projects.findByDesignerId("designer-1")).thenReturn(List.of(visibleProject));

        Boolean allowed = ReflectionTestUtils.invokeMethod(controller, "canAccessFile",
                new AuthController.AuthSession("designer-1", "designer", "设计师"), storedName, storedName);

        assertTrue(allowed);
    }

    @Test
    void unboundFileNotReferencedByAccessibleProjectRemainsPrivate() {
        String storedName = "private-upload.jpg";
        FileRecordRepository records = mock(FileRecordRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        FileController controller = new FileController(mock(FileArchiveService.class), records, projects, tasks,
                mock(FilePreviewService.class));

        FileRecord pendingUpload = FileRecord.builder()
                .storedName(storedName)
                .originalName("private.jpg")
                .fileSize(10L)
                .ownerUserId("another-user")
                .storageTier("local")
                .build();

        when(records.findByStoredName(storedName)).thenReturn(Optional.of(pendingUpload));
        when(projects.findByDesignerId("designer-1")).thenReturn(List.of());

        Boolean allowed = ReflectionTestUtils.invokeMethod(controller, "canAccessFile",
                new AuthController.AuthSession("designer-1", "designer", "设计师"), storedName, storedName);

        assertFalse(allowed);
    }

    @Test
    void designerCanOpenFileFromAnotherDesignersTaskInSameVisibleProject() {
        String storedName = "other-designer-reference.jpg";
        FileRecordRepository records = mock(FileRecordRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        FileController controller = new FileController(mock(FileArchiveService.class), records, projects, tasks,
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
        when(projects.findByDesignerId("designer-1")).thenReturn(List.of(visibleProject));
        when(tasks.countFileReferencesByProjectIds(List.of(2L), storedName)).thenReturn(1L);

        Boolean allowed = ReflectionTestUtils.invokeMethod(controller, "canAccessFile",
                new AuthController.AuthSession("designer-1", "designer", "设计师"), storedName, storedName);

        assertTrue(allowed);
    }
}
