package com.example.llmchat.mcp;

import com.example.llmchat.dto.OrchestrationRunRequest;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects Day 20 multi-server orchestration prompts so task FSM / invariants do not block MCP tool flows.
 */
public final class McpOrchestrationPromptDetector {

    private static final Pattern FILENAME = Pattern.compile(
            "([\\w\\-]+\\.txt)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private McpOrchestrationPromptDetector() {
    }

    public static boolean isExamPrepOrchestration(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.trim().toLowerCase(Locale.ROOT);
        boolean wantsReminder = normalized.contains("напомин")
                || normalized.contains("schedulereminder")
                || normalized.contains("60 сек");
        boolean wantsPipeline = normalized.contains("pipeline")
                || normalized.contains("конспект")
                || normalized.contains("сохран")
                || normalized.contains(".txt");
        boolean wantsStudy = normalized.contains("справочник")
                || normalized.contains("searchtopic")
                || normalized.contains("найди тему")
                || normalized.contains("иман");
        boolean explicit = normalized.contains("подготовь меня к экзамену")
                || normalized.contains("iman-orchestration")
                || normalized.contains("оркестрац")
                || normalized.contains("[rest-оркестратор]");
        return explicit || (wantsReminder && wantsPipeline && wantsStudy);
    }

    public static OrchestrationRunRequest toExamPrepRequest(String message) {
        String normalized = message.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);

        String query = "иман";
        if (lower.contains("иман")) {
            query = "иман";
        } else if (lower.contains("ислам")) {
            query = "ислам";
        }

        String filename = "iman-orchestration.txt";
        Matcher filenameMatcher = FILENAME.matcher(normalized);
        if (filenameMatcher.find()) {
            filename = filenameMatcher.group(1);
        } else if (lower.contains("iman-orchestration")) {
            filename = "iman-orchestration.txt";
        }

        String topic = lower.contains("ислам") || lower.contains("иман") ? "ислам" : null;

        return new OrchestrationRunRequest(
                McpOrchestrationService.SCENARIO_EXAM_PREP,
                query,
                filename,
                topic);
    }
}
