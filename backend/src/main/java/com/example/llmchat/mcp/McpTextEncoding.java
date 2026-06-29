package com.example.llmchat.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Fixes common MCP STDIO mojibake for Cyrillic text on Windows.
 * Typical case: UTF-8 bytes misread as Windows-1251 → "РЁРµСЃС‚СЊ" instead of "Шесть".
 */
public final class McpTextEncoding {

    private static final Charset CP1251 = Charset.forName("Windows-1251");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpTextEncoding() {
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (!looksLikeMojibake(value)) {
            return fixTransliteration(value);
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
        if (bestScore > cyrillicLetterScore(value) && looksLikeMojibake(best)) {
            String twice = tryFix(best, CP1251);
            if (twice != null && cyrillicLetterScore(twice) > bestScore && !looksLikeMojibake(twice)) {
                return fixTransliteration(twice);
            }
        }
        return fixTransliteration(best);
    }

    public static String fixTransliteration(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value
                .replace("кiyas", "кийас")
                .replace("kiyas", "кийас")
                .replace("Kiyas", "кийас")
                .replace("Кiyas", "кийас");
    }

    public static String normalizeJson(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            return MAPPER.writeValueAsString(normalizeNode(root));
        } catch (Exception exception) {
            return normalize(json);
        }
    }

    private static JsonNode normalizeNode(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(normalize(node.asText()));
        }
        if (node.isArray()) {
            ArrayNode array = MAPPER.createArrayNode();
            node.forEach(child -> array.add(normalizeNode(child)));
            return array;
        }
        if (node.isObject()) {
            ObjectNode object = MAPPER.createObjectNode();
            node.fields().forEachRemaining(entry -> object.set(entry.getKey(), normalizeNode(entry.getValue())));
            return object;
        }
        return node;
    }

    /**
     * Uppercase Р/С (U+0420/U+0421) appear in CP1251-mojibake, not in normal Russian prose.
     */
    static boolean looksLikeMojibake(String value) {
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
            char ch = value.charAt(i);
            if (Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.CYRILLIC) {
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
