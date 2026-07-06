package com.example.llmchat.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RagKeywords {

    private static final Pattern KEYWORD = Pattern.compile("[\\p{IsAlphabetic}]{4,}");
    private static final Set<String> STOP_WORDS = Set.of(
            "что", "такое", "такая", "такие", "этот", "этого", "этой", "эта", "это",
            "какой", "какая", "какие", "какое", "когда", "где", "кто", "чем", "чему",
            "как", "для", "при", "или", "либо", "если", "тоже", "также", "быть",
            "было", "были", "будет", "может", "можно", "нужно", "надо", "ли",
            "из", "от", "до", "над", "под", "про", "без", "через",
            "кратко", "назовите", "перечислите", "изложите", "опишите", "объясните",
            "скажите", "расскажите", "дайте", "укажите", "напишите", "определите",
            "означает", "значение", "значит", "слово", "слова",
            "основы", "учение", "православ", "православие", "православной", "православная", "православное",
            "церковь", "церкви", "христиан", "христианство", "история", "смысл",
            "святой", "святую", "святая", "святых", "троица", "троице", "троицу");

    private RagKeywords() {
    }

    static List<String> extract(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Matcher matcher = KEYWORD.matcher(query.toLowerCase(Locale.ROOT));
        List<String> keywords = new ArrayList<>();
        while (matcher.find()) {
            String word = matcher.group();
            if (!STOP_WORDS.contains(word)) {
                keywords.add(word);
            }
        }
        return keywords;
    }

    static int countMatches(String content, List<String> keywords) {
        if (content == null || content.isBlank() || keywords.isEmpty()) {
            return 0;
        }
        String haystack = content.toLowerCase(Locale.ROOT);
        int matches = 0;
        for (String keyword : keywords) {
            if (keyword.length() >= 5 && haystack.contains(keyword)) {
                matches++;
            }
        }
        return matches;
    }

    static boolean containsAny(String content, List<String> keywords) {
        return countMatches(content, keywords) > 0;
    }

    static List<String> merge(String... queries) {
        List<String> merged = new ArrayList<>();
        for (String query : queries) {
            if (query == null || query.isBlank()) {
                continue;
            }
            for (String keyword : extract(query)) {
                if (!merged.contains(keyword)) {
                    merged.add(keyword);
                }
            }
        }
        return merged;
    }

    static List<String> strongTerms(List<String> keywords) {
        return keywords.stream()
                .filter(keyword -> keyword.length() >= 6)
                .distinct()
                .toList();
    }
}
