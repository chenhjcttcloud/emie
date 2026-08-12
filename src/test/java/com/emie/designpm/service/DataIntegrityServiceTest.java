package com.emie.designpm.service;

import com.emie.designpm.repository.DesignRequirementRepository;
import com.emie.designpm.repository.FileRecordRepository;
import com.emie.designpm.repository.ProjectRepository;
import com.emie.designpm.repository.SubTaskRepository;
import org.springframework.data.domain.Pageable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataIntegrityServiceTest {
    @Test
    void reportsMissingAndInvalidFileReferencesWithoutMutatingData() {
        ProjectRepository projects = mock(ProjectRepository.class);
        DesignRequirementRepository requirements = mock(DesignRequirementRepository.class);
        FileRecordRepository files = mock(FileRecordRepository.class);
        SubTaskRepository subTasks = mock(SubTaskRepository.class);
        ProjectRepository.IntegrityProjectProjection project = mock(ProjectRepository.IntegrityProjectProjection.class);
        when(project.getId()).thenReturn(8L);
        when(project.getReferenceImagesJson()).thenReturn("[{\"storedName\":\"missing.png\"}]");
        when(project.getAttachmentsJson()).thenReturn("not-json");
        FileRecordRepository.IntegrityFileProjection file = mock(FileRecordRepository.IntegrityFileProjection.class);
        when(file.getId()).thenReturn(1L);
        when(file.getStoredName()).thenReturn("present.png");
        when(projects.findIntegrityProjectsAfter(0L, Pageable.ofSize(500))).thenReturn(List.of(project));
        when(requirements.findIntegrityRequirementsAfter(0L, Pageable.ofSize(500))).thenReturn(List.of());
        when(subTasks.findIntegritySubTasksAfter(0L, Pageable.ofSize(500))).thenReturn(List.of());
        when(files.findIntegrityFilesAfter(0L, Pageable.ofSize(500))).thenReturn(List.of(file));

        Map<String, Object> report = new DataIntegrityService(projects, subTasks, requirements, files).scan();

        assertFalse((Boolean) report.get("healthy"));
        assertEquals(List.of("project#8.referenceImagesJson -> missing.png"), report.get("missingFiles"));
        assertEquals(List.of("project#8.attachmentsJson"), report.get("invalidJson"));
        verify(projects, never()).findAll();
        verify(subTasks, never()).findAll();
        verify(requirements, never()).findAll();
        verify(files, never()).findAll();
    }

    @Test
    void continuesUntilTheFinalPartialBatchWithoutTruncatingResults() {
        ProjectRepository projects = mock(ProjectRepository.class);
        DesignRequirementRepository requirements = mock(DesignRequirementRepository.class);
        FileRecordRepository files = mock(FileRecordRepository.class);
        SubTaskRepository subTasks = mock(SubTaskRepository.class);
        FileRecordRepository.IntegrityFileProjection boundary = mock(FileRecordRepository.IntegrityFileProjection.class);
        FileRecordRepository.IntegrityFileProjection finalRecord = mock(FileRecordRepository.IntegrityFileProjection.class);
        when(boundary.getId()).thenReturn(500L);
        when(boundary.getStoredName()).thenReturn("first.png");
        when(finalRecord.getId()).thenReturn(501L);
        when(finalRecord.getStoredName()).thenReturn("last.png");
        when(files.findIntegrityFilesAfter(0L, Pageable.ofSize(500)))
                .thenReturn(Collections.nCopies(500, boundary));
        when(files.findIntegrityFilesAfter(500L, Pageable.ofSize(500))).thenReturn(List.of(finalRecord));
        when(projects.findIntegrityProjectsAfter(0L, Pageable.ofSize(500))).thenReturn(List.of());
        when(requirements.findIntegrityRequirementsAfter(0L, Pageable.ofSize(500))).thenReturn(List.of());
        when(subTasks.findIntegritySubTasksAfter(0L, Pageable.ofSize(500))).thenReturn(List.of());

        Map<String, Object> report = new DataIntegrityService(projects, subTasks, requirements, files).scan();

        assertEquals(2, report.get("scannedFiles"));
        verify(files).findIntegrityFilesAfter(500L, Pageable.ofSize(500));
    }
}
