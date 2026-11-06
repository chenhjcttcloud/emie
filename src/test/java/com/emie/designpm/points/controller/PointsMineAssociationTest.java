package com.emie.designpm.points.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.entity.PointAdjustmentLedger;
import com.emie.designpm.entity.PointLedger;
import com.emie.designpm.points.service.PointLedgerAssociationService;
import com.emie.designpm.points.service.PointsService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PointsMineAssociationTest {
    @Test
    void mineEnrichesOnlyItsOwnReturnedRowsAndPreservesPagination() throws Exception {
        var points = mock(PointsService.class);
        var associations = mock(PointLedgerAssociationService.class);
        var ownLedger = new PointLedger();
        ownLedger.setId(7L);
        ownLedger.setUserId("d1");
        ownLedger.setSubTaskId(10L);
        var ownAdjustment = new PointAdjustmentLedger();
        ownAdjustment.setId(9L);
        ownAdjustment.setUserId("d1");
        ownAdjustment.setSourceType("DESIGN_REQUIREMENT");
        when(points.ledgerPage(eq("d1"), any()))
                .thenReturn(new PageImpl<>(List.of(ownLedger), PageRequest.of(0, 50), 51));
        when(points.adjustmentLedger("d1")).thenReturn(List.of(ownAdjustment));
        when(points.balance("d1")).thenReturn(12.5);
        when(associations.associations("d1", List.of(7L), List.of(9L)))
                .thenReturn(Map.of("L-7", Map.of("projectId", 100L, "projectName", "本人项目")));
        var mvc = MockMvcBuilders.standaloneSetup(new PointsController(points, null, null, associations))
                .build();

        mvc.perform(get("/api/points/me")
                        .param("page", "-4")
                        .param("size", "800")
                        .param("userId", "d2")
                        .requestAttr("authSession", new AuthSession("d1", "designer", "本人")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("d1"))
                .andExpect(jsonPath("$.balance").value(12.5))
                .andExpect(jsonPath("$.ledger[0].id").value(7))
                .andExpect(jsonPath("$.adjustmentLedger[0].id").value(9))
                .andExpect(jsonPath("$.ledgerTotal").value(51))
                .andExpect(jsonPath("$.ledgerPages").value(2))
                .andExpect(jsonPath("$.ledgerAssociations.L-7.projectName").value("本人项目"));
        var paging = ArgumentCaptor.forClass(Pageable.class);
        verify(points).ledgerPage(eq("d1"), paging.capture());
        org.junit.jupiter.api.Assertions.assertEquals(0, paging.getValue().getPageNumber());
        org.junit.jupiter.api.Assertions.assertEquals(50, paging.getValue().getPageSize());
        verify(associations).associations("d1", List.of(7L), List.of(9L));
        verify(points).adjustmentLedger("d1");
        verify(points).balance("d1");
        verifyNoMoreInteractions(points, associations);
    }
}
