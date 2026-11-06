package com.emie.designpm.util;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Repairs UTF-8 text stored through legacy Latin-1/Windows-1252 decoding without replacing characters. */
public final class TextEncodingUtil {
    // MySQL latin1 uses Windows-1252's printable characters, but legacy rows can
    // also contain the original ISO-8859-1 C1 controls, including undefined CP1252 bytes.
    private static final String WINDOWS_1252_C1 =
            "\u20ac\u0081\u201a\u0192\u201e\u2026\u2020\u2021\u02c6\u2030\u0160\u2039\u0152\u008d\u017d\u008f"
                    + "\u0090\u2018\u2019\u201c\u201d\u2022\u2013\u2014\u02dc\u2122\u0161\u203a\u0153\u009d\u017e\u0178";

    private TextEncodingUtil() {}

    public static String repairUtf8Mojibake(String value) {
        if (value == null || value.isBlank()) return value;
        String best = value;
        for (int i = 0; i < 3; i++) {
            String candidate = decodeLegacyBytes(best);
            if (candidate == null || candidate.indexOf('\uFFFD') >= 0 || candidate.equals(best)) break;
            best = candidate;
        }
        return best;
    }

    private static String decodeLegacyBytes(String value) {
        byte[] bytes = new byte[value.length()];
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character <= 0xff) {
                bytes[i] = (byte) character;
            } else {
                int legacyOffset = WINDOWS_1252_C1.indexOf(character);
                // Decline mixed healthy Unicode rather than converting CJK or emoji to '?'.
                if (legacyOffset < 0) return null;
                bytes[i] = (byte) (0x80 + legacyOffset);
            }
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException invalidUtf8) {
            return null;
        }
    }
}
