package com.emie.designpm.util;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Repairs legacy UTF-8 text that was decoded as Windows-1252 before storage. */
public final class TextEncodingUtil {
    private static final Charset LEGACY_SINGLE_BYTE = Charset.forName("windows-1252");

    private TextEncodingUtil() {}

    public static String repairUtf8Mojibake(String value) {
        if (value == null || value.isBlank()) return value;
        String best = value;
        for (int i = 0; i < 3; i++) {
            if (!looksLikeMojibake(best)) break;
            String candidate = new String(best.getBytes(LEGACY_SINGLE_BYTE), StandardCharsets.UTF_8);
            if (candidate.indexOf('\uFFFD') >= 0 || candidate.equals(best)) break;
            best = candidate;
        }
        return best;
    }

    private static boolean looksLikeMojibake(String value) {
        return value.indexOf('Ã') >= 0 || value.indexOf('Â') >= 0 || value.indexOf('å') >= 0
                || value.indexOf('æ') >= 0 || value.indexOf('è') >= 0 || value.indexOf('é') >= 0
                || value.indexOf('†') >= 0;
    }
}
