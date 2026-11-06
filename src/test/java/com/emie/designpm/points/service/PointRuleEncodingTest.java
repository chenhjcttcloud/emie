package com.emie.designpm.points.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.entity.PointRule;
import com.emie.designpm.points.repository.PointLedgerRepository;
import com.emie.designpm.points.repository.PointRuleRepository;
import com.emie.designpm.scoring.repository.ScoringRepository;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class PointRuleEncodingTest {
    private final PointRuleRepository repository = mock(PointRuleRepository.class);
    private final PointsService service =
            new PointsService(repository, mock(PointLedgerRepository.class), mock(ScoringRepository.class));

    @Test
    void returnsRepairedRuleTextWithoutChangingStoredRulesOrOtherFields() {
        String description = "小型展会｜基础展墙+展架+少量平面；按整体项目计分，包含正常调整";
        String storedDescription = mysqlLatin1Mojibake(description);
        assertTrue(
                storedDescription.indexOf('\u008f') >= 0, "Fixture must retain MySQL latin1's undefined byte mapping");
        PointRule stored = rule("D30_22");
        stored.setCategory("å±•ä¼šç‰©æ–™");
        stored.setSubcategory(mysqlLatin1Mojibake("展示物料"));
        stored.setDescription(storedDescription);
        when(repository.findAllByOrderByRuleCodeAsc()).thenReturn(List.of(stored));

        PointRule displayed = service.rules().getFirst();

        assertNotSame(stored, displayed);
        assertEquals("展会物料", displayed.getCategory());
        assertEquals("展示物料", displayed.getSubcategory());
        assertEquals(description, displayed.getDescription());
        assertEquals("å±•ä¼šç‰©æ–™", stored.getCategory());
        assertEquals(mysqlLatin1Mojibake("展示物料"), stored.getSubcategory());
        assertEquals(storedDescription, stored.getDescription());
        assertAll(
                () -> assertEquals(stored.getId(), displayed.getId()),
                () -> assertEquals(stored.getRuleCode(), displayed.getRuleCode()),
                () -> assertEquals(stored.getPoints(), displayed.getPoints()),
                () -> assertEquals(stored.isEnabled(), displayed.isEnabled()),
                () -> assertEquals(stored.isCountInPerformance(), displayed.isCountInPerformance()),
                () -> assertEquals(stored.getQualityBonusThreshold(), displayed.getQualityBonusThreshold()),
                () -> assertEquals(stored.getQualityBonusRatio(), displayed.getQualityBonusRatio()),
                () -> assertEquals(stored.getQualityTopThreshold(), displayed.getQualityTopThreshold()),
                () -> assertEquals(stored.getQualityTopRatio(), displayed.getQualityTopRatio()),
                () -> assertEquals(stored.getMaxTotalMultiplier(), displayed.getMaxTotalMultiplier()),
                () -> assertEquals(stored.getCreatedAt(), displayed.getCreatedAt()),
                () -> assertEquals(stored.getUpdatedAt(), displayed.getUpdatedAt()));
        verify(repository).findAllByOrderByRuleCodeAsc();
        verifyNoMoreInteractions(repository);
    }

    @Test
    void preservesCustomNormalRuleTextAndNullFields() {
        PointRule custom = rule("CUSTOM_NORDIC");
        custom.setCategory("自定义展览设计");
        custom.setSubcategory("北欧 é 展示");
        custom.setDescription("原创新品 Résumé／展示：北欧展会（内部自定义）");
        PointRule optional = rule("CUSTOM_OPTIONAL");
        optional.setCategory(null);
        optional.setSubcategory(null);
        optional.setDescription("");
        when(repository.findAllByOrderByRuleCodeAsc()).thenReturn(List.of(custom, optional));

        var displayed = service.rules();

        assertEquals(custom, displayed.getFirst());
        assertNotSame(custom, displayed.getFirst());
        assertEquals(optional, displayed.get(1));
        assertNotSame(optional, displayed.get(1));
        verify(repository).findAllByOrderByRuleCodeAsc();
        verifyNoMoreInteractions(repository);
    }

    private PointRule rule(String code) {
        PointRule rule = new PointRule();
        rule.setId(123L);
        rule.setRuleCode(code);
        rule.setPoints(12.5);
        rule.setEnabled(false);
        rule.setCountInPerformance(false);
        rule.setQualityBonusThreshold(90);
        rule.setQualityBonusRatio(0.25);
        rule.setQualityTopThreshold(98);
        rule.setQualityTopRatio(0.6);
        rule.setMaxTotalMultiplier(2.5);
        rule.setCreatedAt(LocalDateTime.of(2026, 10, 1, 10, 0));
        rule.setUpdatedAt(LocalDateTime.of(2026, 11, 5, 10, 0));
        return rule;
    }

    // MySQL latin1 follows Windows-1252 but preserves its undefined bytes as C1 controls.
    private String mysqlLatin1Mojibake(String value) {
        Charset windows1252 = Charset.forName("windows-1252");
        StringBuilder corrupted = new StringBuilder();
        for (byte valueByte : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = Byte.toUnsignedInt(valueByte);
            if (unsigned == 0x81 || unsigned == 0x8d || unsigned == 0x8f || unsigned == 0x90 || unsigned == 0x9d)
                corrupted.append((char) unsigned);
            else corrupted.append(new String(new byte[] {valueByte}, windows1252));
        }
        return corrupted.toString();
    }
}
