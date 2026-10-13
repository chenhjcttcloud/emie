package com.emie.designpm.performance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.designrequirement.repository.DesignRequirementRepository;
import com.emie.designpm.entity.DesignRequirement;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.entity.SubTaskDeliveryVersion;
import com.emie.designpm.project.repository.SubTaskDeliveryVersionRepository;
import com.emie.designpm.project.repository.SubTaskRepository;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DesignerMonthlyDeliveryServiceTest {
    @Test
    void loadsSavedVersionsInOrderAndOnlyReturnsSafePreviewFiles() {
        SubTaskRepository tasks = mock(SubTaskRepository.class);
        SubTaskDeliveryVersionRepository versions = mock(SubTaskDeliveryVersionRepository.class);
        DesignRequirementRepository requirements = mock(DesignRequirementRepository.class);
        SubTask task = new SubTask();
        task.setId(10L);
        task.setDesignerId("designer-1");
        task.setCompletedAt(LocalDateTime.of(2026, 9, 20, 12, 0));
        when(tasks.findByIdWithProject(10L)).thenReturn(Optional.of(task));

        SubTaskDeliveryVersion first = new SubTaskDeliveryVersion();
        first.setVersionNo(1);
        first.setReferenceImagesJson("[{\"name\":\"封面.png\",\"url\":\"/api/files/download/cover.png\"}]");
        first.setAttachmentsJson("[{\"name\":\"源稿.ai\",\"url\":\"/api/files/download/source.ai\"}]");
        SubTaskDeliveryVersion second = new SubTaskDeliveryVersion();
        second.setVersionNo(2);
        second.setAttachmentsJson(
                "[{\"name\":\"危险文件\",\"url\":\"https://example.com/api/files/download/../secret.ai\"}]");
        when(versions.findBySubTaskIdOrderByVersionNoDesc(10L)).thenReturn(List.of(second, first));

        List<DesignerMonthlyDeliveryService.DeliveryPreview> result = new DesignerMonthlyDeliveryService(
                        tasks, versions, requirements)
                .forCompletedTask(YearMonth.of(2026, 9), 10L);
        assertEquals(
                List.of(1, 2),
                result.stream()
                        .map(DesignerMonthlyDeliveryService.DeliveryPreview::versionNo)
                        .toList());
        assertEquals(
                List.of("image", "ai"),
                result.getFirst().files().stream()
                        .map(DesignerMonthlyDeliveryService.PreviewFile::kind)
                        .toList());
        assertTrue(result.get(1).files().isEmpty());
        assertThrows(
                IllegalArgumentException.class, () -> new DesignerMonthlyDeliveryService(tasks, versions, requirements)
                        .forCompletedTask(YearMonth.of(2026, 8), 10L));
        verify(versions, times(1)).findBySubTaskIdOrderByVersionNoDesc(10L);
    }

    @Test
    void loadsCompletedRequirementDeliveryInSelectedMonth() {
        DesignRequirementRepository requirements = mock(DesignRequirementRepository.class);
        DesignRequirement requirement = new DesignRequirement();
        requirement.setId(21L);
        requirement.setDesignerId("designer-1");
        requirement.setStatus("completed");
        requirement.setUpdatedAt(LocalDateTime.of(2026, 9, 18, 12, 0));
        requirement.setDeliveredAt(LocalDateTime.of(2026, 8, 31, 12, 0));
        requirement.setDeliveryAttachmentsJson("[{\"name\":\"方案.ai\",\"url\":\"/api/files/download/design.ai\"}]");
        when(requirements.findById(21L)).thenReturn(Optional.of(requirement));
        var service = new DesignerMonthlyDeliveryService(
                mock(SubTaskRepository.class), mock(SubTaskDeliveryVersionRepository.class), requirements);
        assertEquals(
                "ai",
                service.forCompletedRequirement(YearMonth.of(2026, 8), 21L)
                        .getFirst()
                        .files()
                        .getFirst()
                        .kind());
        assertThrows(IllegalArgumentException.class, () -> service.forCompletedRequirement(YearMonth.of(2026, 9), 21L));
    }
}
