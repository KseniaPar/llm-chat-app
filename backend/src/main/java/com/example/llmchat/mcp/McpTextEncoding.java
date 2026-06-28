package com.example.llmchat.mcp;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Fixes common MCP STDIO mojibake for Cyrillic text on Windows.
 */
public final class McpTextEncoding {

    private McpTextEncoding() {
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (!looksLikeMojibake(value)) {
            return value;
        }
        String fixed = tryFix(value, StandardCharsets.ISO_8859_1);
        if (fixed != null && containsCyrillic(fixed)) {
            return fixed;
        }
        fixed = tryFix(value, Charset.forName("Windows-1252"));
        if (fixed != null && containsCyrillic(fixed)) {
            return fixed;
        }
        return value;
    }

    private static boolean looksLikeMojibake(String value) {
        return value.indexOf('Р') >= 0 || value.indexOf('С') >= 0 || value.indexOf('Ð') >= 0;
    }

    private static boolean containsCyrillic(String value) {
        return value.chars().anyMatch(ch -> Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.CYRILLIC);
    }

    private static String tryFix(String value, Charset sourceCharset) {
        try {
            return new String(value.getBytes(sourceCharset), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            return null;
        }
    }
}
