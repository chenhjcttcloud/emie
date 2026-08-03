package com.emie.designpm.service;

import com.emie.designpm.entity.FileRecord;
import com.emie.designpm.entity.Project;
import com.emie.designpm.repository.DesignRequirementRepository;
import com.emie.designpm.repository.FileRecordRepository;
import com.emie.designpm.repository.ProjectRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataIntegrityServiceTest {
    @Test
    void reportsMissingAndInvalidFileReferencesWithoutMutatingData() {
        ProjectRepository projects = mock(ProjectRepository.class);
        DesignRequirementRepository requirements = mock(DesignRequirementRepository.class);
        FileRecordRepository files = mock(FileRecordRepository.class);
        Project project = new Project();
        project.setId(8L);
        project.setReferenceImagesJson("[{\"storedName\":\"missing.png\"}]");
        project.setAttachmentsJson("not-json");
        when(projects.findAll()).thenReturn(List.of(project));
        when(requirements.findAll()).thenReturn(List.of());
        when(files.findAll()).thenReturn(List.of(FileRecord.builder().storedName("present.png").build()));

        Map<String, Object> report = new DataIntegrityService(projects, requirements, files).scan();

        assertFalse((Boolean) report.get("healthy"));
        assertEquals(List.of("project#8.referenceImagesJson -> missing.png"), report.get("missingFiles"));
        assertEquals(List.of("project#8.attachmentsJson"), report.get("invalidJson"));
    }
}
