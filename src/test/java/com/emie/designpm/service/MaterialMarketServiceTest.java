package com.emie.designpm.service;

import com.emie.designpm.entity.FileRecord;
import com.emie.designpm.entity.IpOption;
import com.emie.designpm.entity.MaterialMarketItem;
import com.emie.designpm.entity.User;
import com.emie.designpm.repository.FileRecordRepository;
import com.emie.designpm.repository.IpOptionRepository;
import com.emie.designpm.repository.MaterialMarketItemRepository;
import com.emie.designpm.repository.MaterialMarketLikeRepository;
import com.emie.designpm.repository.MaterialMarketAdoptionRepository;
import com.emie.designpm.repository.PointAdjustmentLedgerRepository;
import com.emie.designpm.repository.ProjectRepository;
import com.emie.designpm.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;

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
                "title", "桌面收纳灯", "description", "便携设计", "ipName", "EMIE", "category", "id",
                "filesJson", "[{\"storedName\":\"attachment.pdf\",\"url\":\"javascript:alert(1)\"}]",
                "referenceImagesJson", "[{\"storedName\":\"reference.png\"}]"), "designer_01");

        assertTrue(published.getMaterialFilesJson().contains("\"storedName\":\"attachment.pdf\""));
        assertTrue(published.getMaterialFilesJson().contains("\"url\":\"/api/files/download/attachment.pdf\""));
        assertTrue(published.getReferenceImagesJson().contains("\"storedName\":\"reference.png\""));
        assertTrue(published.getReferenceImagesJson().contains("\"url\":\"/api/files/download/reference.png\""));
        assertEquals("id", published.getCategory());
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
                "category", "graphic", "filesJson", "[{\"storedName\":\"other.pdf\"}]"), "designer_01"));
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
                "title", "灯", "description", "描述", "ipName", "EMIE", "category", "visual", "filesJson", "[]",
                "referenceImagesJson", "[{\"storedName\":\"reference.png\"}]"), "designer_01");

        assertTrue(published.getMaterialFilesJson().equals("[]"));
    }

    @Test
    void publishRejectsUnknownMaterialCategory() {
        MaterialMarketItemRepository materials = mock(MaterialMarketItemRepository.class);
        UserRepository users = mock(UserRepository.class);
        User designer = User.builder().userId("designer_01").name("设计师").role("designer").build();
        when(users.findByUserId("designer_01")).thenReturn(Optional.of(designer));

        var error = assertThrows(IllegalArgumentException.class, () -> service(materials, users,
                mock(FileRecordRepository.class), mock(FileArchiveService.class), mock(IpOptionRepository.class))
                .publish(Map.of("title", "灯", "description", "描述", "ipName", "EMIE", "category", "other"), "designer_01"));

        assertTrue(error.getMessage().contains("ID、视觉或平面"));
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
                mock(IpOptionRepository.class), mock(PointAdjustmentLedgerRepository.class), notifications,
                mock(MaterialMarketLikeRepository.class), mock(MaterialMarketAdoptionRepository.class)).adopt(1L, "sales_01", "sales", null, "direct");

        verify(notifications).notifyRoleAfterCommit(eq("MATERIAL_MARKET_PLANNER_PENDING"), eq("planner"),
                eq("project"), eq(109L), eq("sales_01"), org.mockito.ArgumentMatchers.argThat(context ->
                        "桌面收纳灯".equals(context.get("projectName"))
                                && "销售小李".equals(context.get("actorName"))
                                && "/?projectId=109".equals(context.get("projectLink"))));
    }

    @Test
    void directAndDesignAdoptionAwardTheRequestedPoints() {
        assertAdoptionPoints("direct", 20, "直接采纳");
        assertAdoptionPoints("design", 10, "设计采纳");
    }

    @Test
    void sameAdoptionTypeCanOnlyBeUsedOncePerMaterial() {
        MaterialMarketItemRepository materials = mock(MaterialMarketItemRepository.class);
        MaterialMarketAdoptionRepository adoptions = mock(MaterialMarketAdoptionRepository.class);
        PointAdjustmentLedgerRepository adjustments = mock(PointAdjustmentLedgerRepository.class);
        MaterialMarketItem material = new MaterialMarketItem(); material.setId(9L); material.setStatus("selected");
        when(materials.lockById(9L)).thenReturn(Optional.of(material));
        when(adoptions.existsByMaterialIdAndAdoptionType(9L, "direct")).thenReturn(true);
        MaterialMarketService service = new MaterialMarketService(materials, mock(ProjectRepository.class), mock(UserRepository.class),
                mock(FileRecordRepository.class), mock(FileArchiveService.class), mock(IpOptionRepository.class),
                adjustments, mock(NotificationWorkflowService.class),
                mock(MaterialMarketLikeRepository.class), adoptions);

        assertThrows(IllegalStateException.class, () -> service.adopt(9L, "sales_01", "sales", null, "direct"));
        verify(adjustments, org.mockito.Mockito.never()).save(any());
    }

    private void assertAdoptionPoints(String adoptionType, int expectedPoints, String expectedReason) {
        MaterialMarketItemRepository materials = mock(MaterialMarketItemRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        UserRepository users = mock(UserRepository.class);
        PointAdjustmentLedgerRepository adjustments = mock(PointAdjustmentLedgerRepository.class);
        MaterialMarketAdoptionRepository adoptions = mock(MaterialMarketAdoptionRepository.class);
        MaterialMarketItem material = new MaterialMarketItem();
        material.setId(7L); material.setTitle("创意灯"); material.setCreatorId("designer_01");
        material.setCreatorName("设计师"); material.setStatus("design".equals(adoptionType) ? "selected" : "available"); material.setProductDescription("说明");
        if ("design".equals(adoptionType)) material.setProjectId(77L);
        material.setReferenceImagesJson("[]"); material.setMaterialFilesJson("[]");
        User planner = User.builder().userId("planner_01").name("企划").role("planner").build();
        when(materials.lockById(7L)).thenReturn(Optional.of(material));
        when(users.findByUserId("planner_01")).thenReturn(Optional.of(planner));
        when(projects.save(any())).thenAnswer(call -> { var project = call.getArgument(0, com.emie.designpm.entity.Project.class); project.setId(88L); return project; });

        new MaterialMarketService(materials, projects, users, mock(FileRecordRepository.class), mock(FileArchiveService.class),
                mock(IpOptionRepository.class), adjustments, mock(NotificationWorkflowService.class),
                mock(MaterialMarketLikeRepository.class), adoptions).adopt(7L, "planner_01", "planner", null, adoptionType);

        var ledger = org.mockito.ArgumentCaptor.forClass(com.emie.designpm.entity.PointAdjustmentLedger.class);
        verify(adjustments).save(ledger.capture());
        assertEquals(expectedPoints, ledger.getValue().getPoints().intValue());
        assertTrue(ledger.getValue().getReason().contains(expectedReason));
        assertEquals(adoptionType, material.getAdoptionType());
        assertEquals(88L, material.getProjectId());
        verify(adoptions).save(org.mockito.ArgumentMatchers.argThat(record -> record.getProjectId().equals(88L)
                && record.getAdoptionType().equals(adoptionType)));
    }

    @Test
    void likeToggleAddsAndRemovesOneLikeWithoutCreatingPoints() {
        MaterialMarketItemRepository materials = mock(MaterialMarketItemRepository.class);
        MaterialMarketLikeRepository likes = mock(MaterialMarketLikeRepository.class);
        PointAdjustmentLedgerRepository adjustments = mock(PointAdjustmentLedgerRepository.class);
        MaterialMarketItem material = new MaterialMarketItem(); material.setId(3L); material.setLikeCount(0);
        when(materials.lockById(3L)).thenReturn(Optional.of(material));
        when(likes.findByMaterialIdAndUserId(3L, "user_1")).thenReturn(Optional.empty());
        MaterialMarketService service = new MaterialMarketService(materials, mock(ProjectRepository.class), mock(UserRepository.class),
                mock(FileRecordRepository.class), mock(FileArchiveService.class), mock(IpOptionRepository.class),
                adjustments, mock(NotificationWorkflowService.class), likes, mock(MaterialMarketAdoptionRepository.class));

        Map<String, Object> result = service.toggleLike(3L, "user_1");

        assertEquals(true, result.get("liked"));
        assertEquals(1, result.get("likeCount"));
        verify(likes).save(any());
        verify(adjustments, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void creatorCannotLikeOwnMaterial() {
        MaterialMarketItemRepository materials = mock(MaterialMarketItemRepository.class);
        MaterialMarketLikeRepository likes = mock(MaterialMarketLikeRepository.class);
        MaterialMarketItem material = new MaterialMarketItem(); material.setId(4L); material.setCreatorId("designer_01");
        when(materials.lockById(4L)).thenReturn(Optional.of(material));
        MaterialMarketService service = new MaterialMarketService(materials, mock(ProjectRepository.class), mock(UserRepository.class),
                mock(FileRecordRepository.class), mock(FileArchiveService.class), mock(IpOptionRepository.class),
                mock(PointAdjustmentLedgerRepository.class), mock(NotificationWorkflowService.class), likes, mock(MaterialMarketAdoptionRepository.class));

        var error = assertThrows(IllegalStateException.class, () -> service.toggleLike(4L, "designer_01"));

        assertEquals("不能给自己的作品点赞", error.getMessage());
        verify(likes, org.mockito.Mockito.never()).save(any());
    }

    private MaterialMarketService service(MaterialMarketItemRepository materials, UserRepository users,
                                          FileRecordRepository records, FileArchiveService archive, IpOptionRepository ips) {
        return new MaterialMarketService(materials, mock(ProjectRepository.class), users, records, archive, ips,
                mock(PointAdjustmentLedgerRepository.class), mock(NotificationWorkflowService.class),
                mock(MaterialMarketLikeRepository.class), mock(MaterialMarketAdoptionRepository.class));
    }
}
