package com.emie.designpm.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextEncodingUtilTest {
    @Test
    void repairsWindows1252Utf8Mojibake() {
        assertEquals("冒烟设计师", TextEncodingUtil.repairUtf8Mojibake("å†’çƒŸè®¾è®¡å¸ˆ"));
    }

    @Test
    void leavesNormalTextUntouched() {
        assertEquals("陈月珍", TextEncodingUtil.repairUtf8Mojibake("陈月珍"));
    }
}
