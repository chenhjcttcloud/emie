package com.emie.designpm.service;

import com.emie.designpm.entity.FileRecord;
import com.emie.designpm.entity.IpOption;
import com.emie.designpm.entity.MaterialMarketItem;
import com.emie.designpm.entity.User;
import com.emie.designpm.repository.FileRecordRepository;
import com.emie.designpm.repository.IpOptionRepository;
import com.emie.designpm.repository.MaterialMarketItemRepository;
import com.emie.designpm.repository.PointAdjustmentLedgerRepository;
import com.emie.designpm.repository.PointRuleRepository;
import com.emie.designpm.repository.ProjectRepository;
import com.emie.designpm.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MaterialMarketServiceTest {
    @Test
    void publishCanonicalizesOwnedUploadsAndBindsBothFileGroups() {
        MaterialMarketItemRepository materials = mock(MaterialMarketItemRepository.class);
        UserRepository users = mock(UserRepository.class);
        FileRecordRepository records = mock(FileRecordRepository.class);
        FileArchiveService archive = mock(FileArchiveService.class);
        IpOptionRepository ips = mock(IpOptionRepository.class);
        User designer = User.builder().userId("designer_01").name("设计师").role("designer").build();
        IpOption ip = new IpOption("EMIE", 1);
        FileRecord attachment = FileRecord.builder().storedName("attachment.pdf").originalName("方案.pdf")
                .fileSize(12L).ownerUserId("designer_01").build();
        FileRecord reference = FileRecord.builder().storedName("reference.png").originalName("参考.png")
                .fileSize(24L).ownerUserId("designer_01").build();
        when(users.findByUserId("designer_01")).thenReturn(Optional.of(designer));
        when(ips.findByName("EMIE")).thenReturn(Optional.of(ip));
        when(records.findByStoredName("attachment.pdf")).thenReturn(Optional.of(attachment));
        when(records.findByStoredName("reference.png")).thenReturn(Optional.of(reference));
        when(materials.save(org.mockito.ArgumentMatchers.any(MaterialMarketItem.class))).thenAnswer(call -> {
            MaterialMarketItem item = call.getArgument(0);
            item.setId(8L);
            return item;
        });

        MaterialMarketItem published = service(materials, users, records, archive, ips).publish(Map.of(
                "title", "桌面收纳灯", "description", "便携设计", "ipName", "EMIE",
                "filesJson", "[{\"storedName\":\"attachment.pdf\",\"url\":\"javascript:alert(1)\"}]",
                "referenceImagesJson", "[{\"storedName\":\"reference.png\"}]"), "designer_01");

        assertTrue(published.getMaterialFilesJson().contains("\"storedName\":\"attachment.pdf\""));
        assertTrue(published.getMaterialFilesJson().contains("\"url\":\"/api/files/download/attachment.pdf\""));
        assertTrue(published.getReferenceImagesJson().contains("\"storedName\":\"reference.png\""));
        assertTrue(published.getReferenceImagesJson().contains("\"url\":\"/api/files/download/reference.png\""));
        verify(archive).bindFilesFromJson(contains("attachment.pdf"), eq("material_market"), eq(8L));
        verify(archive).bindFilesFromJson(contains("reference.png"), eq("material_market"), eq(8L));
    }

    @Test
    void publishRejectsFilesNotOwnedByTheDesigner() {
        MaterialMarketItemRepository materials = mock(MaterialMarketItemRepository.class);
        UserRepository users = mock(UserRepository.class);
        FileRecordRepository records = mock(FileRecordRepository.class);
        IpOptionRepository ips = mock(IpOptionRepository.class);
        User designer = User.builder().userId("designer_01").name("设计师").role("designer").build();
        IpOption ip = new IpOption("EMIE", 1);
        FileRecord otherUsersFile = FileRecord.builder().storedName("other.pdf").originalName("他人.pdf")
                .fileSize(12L).ownerUserId("designer_02").build();
        when(users.findByUserId("designer_01")).thenReturn(Optional.of(designer));
        when(ips.findByName("EMIE")).thenReturn(Optional.of(ip));
        when(records.findByStoredName("other.pdf")).thenReturn(Optional.of(otherUsersFile));

        assertThrows(IllegalArgumentException.class, () -> service(materials, users, records,
                mock(FileArchiveService.class), ips).publish(Map.of("title", "灯", "description", "描述", "ipName", "EMIE",
                "filesJson", "[{\"storedName\":\"other.pdf\"}]"), "designer_01"));
    }

    @Test
    void publishAllowsAnEmptyAttachmentListWhenReferenceImagesArePresent() {
        MaterialMarketItemRepository materials = mock(MaterialMarketItemRepository.class);
        UserRepository users = mock(UserRepository.class);
        FileRecordRepository records = mock(FileRecordRepository.class);
        IpOptionRepository ips = mock(IpOptionRepository.class);
        User designer = User.builder().userId("designer_01").name("设计师").role("designer").build();
        IpOption ip = new IpOption("EMIE", 1);
        FileRecord reference = FileRecord.builder().storedName("reference.png").originalName("参考.png")
                .fileSize(24L).ownerUserId("designer_01").build();
        when(users.findByUserId("designer_01")).thenReturn(Optional.of(designer));
        when(ips.findByName("EMIE")).thenReturn(Optional.of(ip));
        when(records.findByStoredName("reference.png")).thenReturn(Optional.of(reference));
        when(materials.save(org.mockito.ArgumentMatchers.any(MaterialMarketItem.class))).thenAnswer(call -> call.getArgument(0));

        MaterialMarketItem published = service(materials, users, records, mock(FileArchiveService.class), ips).publish(Map.of(
                "title", "灯", "description", "描述", "ipName", "EMIE", "filesJson", "[]",
                "referenceImagesJson", "[{\"storedName\":\"reference.png\"}]"), "designer_01");

        assertTrue(published.getMaterialFilesJson().equals("[]"));
    }

    @Test
    void salesSelectionNotifiesEveryActivePlannerWithTheMaterialMarketEvent() {
        MaterialMarketItemRepository materials = mock(MaterialMarketItemRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        UserRepository users = mock(UserRepository.class);
        NotificationWorkflowService notifications = mock(NotificationWorkflowService.class);
        MaterialMarketItem material = new MaterialMarketItem();
        material.setId(1L);
        material.setTitle("桌面收纳灯");
        material.setStatus("available");
        material.setProductDescription("便携设计");
        material.setReferenceImagesJson("[]");
        material.setMaterialFilesJson("[]");
        User sales = User.builder().userId("sales_01").name("销售小李").role("sales").status("active").build();
        when(materials.lockById(1L)).thenReturn(Optional.of(material));
        when(users.findByUserId("sales_01")).thenReturn(Optional.of(sales));
        when(projects.save(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            var project = call.getArgument(0, com.emie.designpm.entity.Project.class);
            project.setId(109L);
            return project;
        });

        new MaterialMarketService(materials, projects, users, mock(FileRecordRepository.class), mock(FileArchiveService.class),
                mock(IpOptionRepository.class), mock(PointRuleRepository.class), mock(PointAdjustmentLedgerRepository.class), notifications)
                .select(1L, "sales_01", "sales", null);

        verify(notifications).notifyRoleAfterCommit(eq("MATERIAL_MARKET_PLANNER_PENDING"), eq("planner"),
                eq("project"), eq(109L), eq("sales_01"), org.mockito.ArgumentMatchers.argThat(context ->
                        "桌面收纳灯".equals(context.get("projectName"))
                                && "销售小李".equals(context.get("actorName"))
                                && "/?projectId=109".equals(context.get("projectLink"))));
    }

    private MaterialMarketService service(MaterialMarketItemRepository materials, UserRepository users,
                                          FileRecordRepository records, FileArchiveService archive, IpOptionRepository ips) {
        return new MaterialMarketService(materials, mock(ProjectRepository.class), users, records, archive, ips,
                mock(PointRuleRepository.class), mock(PointAdjustmentLedgerRepository.class), mock(NotificationWorkflowService.class));
    }
}
