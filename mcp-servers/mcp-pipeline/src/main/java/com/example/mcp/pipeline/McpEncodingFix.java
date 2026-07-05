package com.example.mcp.pipeline;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

final class McpEncodingFix {

    private static final Charset CP1251 = Charset.forName("Windows-1251");

    private McpEncodingFix() {
    }

    static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (!looksLikeMojibake(value)) {
            return value;
        }
        String best = value;
        int bestScore = cyrillicLetterScore(value);
        for (Charset charset : new Charset[] {CP1251, StandardCharsets.ISO_8859_1, Charset.forName("Windows-1252")}) {
            String candidate = tryFix(value, charset);
            if (candidate == null) {
                continue;
            }
            int score = cyrillicLetterScore(candidate);
            if (score > bestScore && !looksLikeMojibake(candidate)) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    static String fixTransliteration(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value
                .replace("кiyas", "кийас")
                .replace("kiyas", "кийас")
                .replace("Kiyas", "кийас")
                .replace("Кiyas", "кийас");
    }

    static String normalizeFull(String value) {
        return fixTransliteration(normalize(value));
    }

    private static boolean looksLikeMojibake(String value) {
        if (value.indexOf('Ð') >= 0 || value.indexOf('Ñ') >= 0) {
            return true;
        }
        int uppercaseMarkers = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == 'Р' || ch == 'С') {
                uppercaseMarkers++;
            }
        }
        if (uppercaseMarkers >= 2) {
            return true;
        }
        return value.indexOf('\uFFFD') >= 0 && uppercaseMarkers >= 1;
    }

    private static int cyrillicLetterScore(String value) {
        int score = 0;
        for (int i = 0; i < value.length(); i++) {
            if (Character.UnicodeBlock.of(value.charAt(i)) == Character.UnicodeBlock.CYRILLIC) {
                score++;
            }
        }
        return score;
    }

    private static String tryFix(String value, Charset sourceCharset) {
        try {
            return new String(value.getBytes(sourceCharset), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            return null;
        }
    }
}
