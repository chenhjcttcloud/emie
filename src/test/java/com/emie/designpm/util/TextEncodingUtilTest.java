package com.emie.designpm.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TextEncodingUtilTest {
    @Test
    void repairsWindows1252Utf8Mojibake() {
        assertEquals("冒烟设计师", TextEncodingUtil.repairUtf8Mojibake("å†’çƒŸè®¾è®¡å¸ˆ"));
    }

    @Test
    void repairsActualRuleCategoryAndMixedMysqlLatin1Description() {
        assertEquals("展会物料", TextEncodingUtil.repairUtf8Mojibake("å±•ä¼šç‰©æ–™"));
        assertEquals(
                "小型展会｜基础展墙+展架+少量平面；按整体项目计分，包含正常调整",
                TextEncodingUtil.repairUtf8Mojibake(
                        "å°\u008fåž‹å±•ä¼šï½œåŸºç¡€å±•å¢™+å±•æž¶+å°‘é‡\u008få¹³é\u009d¢ï¼›æŒ‰æ•´ä½“é¡¹ç›®è®¡åˆ†ï¼ŒåŒ…å\u0090«æ­£å¸¸è°ƒæ•´"));
    }

    @Test
    void repairsIsoLatin1ControlsAndCategoryPunctuation() {
        assertEquals("包装设计", TextEncodingUtil.repairUtf8Mojibake("å\u008c\u0085è£\u0085è®¾è®¡"));
        for (String original : new String[] {"平面与视觉（A类）", "（A类）", "🎨设计积分", "项目1：包装 / 3D + UI"}) {
            assertEquals(
                    original,
                    TextEncodingUtil.repairUtf8Mojibake(garble(original, StandardCharsets.ISO_8859_1)),
                    original);
        }
    }

    @Test
    void repairsRepeatedAndAlternatingLegacyDecodingAndIsIdempotent() {
        Charset latin1 = StandardCharsets.ISO_8859_1;
        Charset windows1252 = Charset.forName("windows-1252");
        String original = "包装设计";
        for (Charset first : new Charset[] {latin1, windows1252}) {
            for (Charset second : new Charset[] {latin1, windows1252}) {
                for (Charset third : new Charset[] {latin1, windows1252}) {
                    String corrupted = garble(garble(garble(original, first), second), third);
                    String repaired = TextEncodingUtil.repairUtf8Mojibake(corrupted);
                    assertEquals(original, repaired, first + " / " + second + " / " + third);
                    assertEquals(repaired, TextEncodingUtil.repairUtf8Mojibake(repaired));
                }
                assertEquals(original, TextEncodingUtil.repairUtf8Mojibake(garble(garble(original, first), second)));
            }
            assertEquals(original, TextEncodingUtil.repairUtf8Mojibake(garble(original, first)));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "陈月珍",
                "平面与视觉（A类）",
                "Café · déjà vu · Ångström · François · São Paulo · München",
                "æøå é è ñ ü",
                "€ ™ © – “smart quotes”",
                "中文 🎨 😊 日本語 Привет",
                "EMIE / A1 / RGB #FFF / 70% · 100.50",
                "",
                "   \t\n"
            })
    void leavesHealthyUnicodeUntouched(String value) {
        assertEquals(value, TextEncodingUtil.repairUtf8Mojibake(value));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "正常中文 · åŒ…è£…è®¾è®¡ · 保留后缀",
                "🎨 åŒ…è£…è®¾è®¡ 😊",
                "å",
                "å°",
                "Ã(",
                "\u00c0\u00af",
                "\u00ed\u00a0\u0080",
                "已经丢失�的字符",
                "ï¿½"
            })
    void declinesUnencodableMixedTextAndIrrecoverableBytesWithoutReplacement(String value) {
        assertEquals(value, TextEncodingUtil.repairUtf8Mojibake(value));
    }

    @Test
    void leavesNullUntouched() {
        assertEquals(null, TextEncodingUtil.repairUtf8Mojibake(null));
    }

    private String garble(String value, Charset legacy) {
        return new String(value.getBytes(StandardCharsets.UTF_8), legacy);
    }
}
