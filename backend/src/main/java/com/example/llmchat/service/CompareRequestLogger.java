package com.example.llmchat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.openai.OpenAiChatOptions;
import com.example.llmchat.dto.ModelMetrics;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class CompareRequestLogger {

    private static final Logger log = LoggerFactory.getLogger(CompareRequestLogger.class);
    private static final String LINE = "============================================================";

    private final ObjectMapper objectMapper;

    public CompareRequestLogger(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public String logModelCompareStart(String prompt) {
        StringBuilder block = new StringBuilder();
        appendLine(block, LINE);
        appendLine(block, "COMPARE - Day 5 (3 model tiers)");
        appendLine(block, "User prompt: \"" + prompt + "\"");
        appendLine(block, LINE);
        return block.toString();
    }

    public String logModelRequest(
            String label,
            String url,
            String model,
            List<Message> messages,
            OpenAiChatOptions options,
            String answer,
            ModelMetrics metrics) {
        StringBuilder block = new StringBuilder();
        appendLine(block, "");
        appendLine(block, "=== " + label + " ===");
        appendLine(block, "POST " + url);
        appendLine(block, "");
        appendLine(block, "Request:");
        appendLine(block, toPrettyJson(buildRequestBody(model, messages, options)));
        appendLine(block, "");
        appendLine(block, "Response:");
        appendLine(block, toPrettyJson(buildResponseBodyWithMetrics(answer, metrics)));
        return block.toString();
    }

    public String logModelSummary(ModelMetrics weak, ModelMetrics medium, ModelMetrics strong, int comparisonLen) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("weak", toMetricsMap(weak));
        summary.put("medium", toMetricsMap(medium));
        summary.put("strong", toMetricsMap(strong));
        summary.put("comparisonChars", comparisonLen);

        StringBuilder block = new StringBuilder();
        appendLine(block, "");
        appendLine(block, "=== SUMMARY (Day 5 model comparison) ===");
        appendLine(block, toPrettyJson(summary));
        appendLine(block, LINE);
        appendLine(block, "");
        return block.toString();
    }

    public String logTemperatureCompareStart(String prompt) {
        StringBuilder block = new StringBuilder();
        appendLine(block, LINE);
        appendLine(block, "COMPARE - Day 4 (3 temperature values)");
        appendLine(block, "User prompt: \"" + prompt + "\"");
        appendLine(block, LINE);
        return block.toString();
    }

    public String logTemperatureSummary(
            int temp0Len,
            int temp07Len,
            int temp12Len,
            int comparisonLen) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("temp0", temp0Len);
        summary.put("temp07", temp07Len);
        summary.put("temp12", temp12Len);
        summary.put("comparison", comparisonLen);

        StringBuilder block = new StringBuilder();
        appendLine(block, "");
        appendLine(block, "=== SUMMARY (response length in chars) ===");
        appendLine(block, toPrettyJson(summary));
        appendLine(block, LINE);
        appendLine(block, "");
        return block.toString();
    }

    public String logCompareStart(String prompt) {
        StringBuilder block = new StringBuilder();
        appendLine(block, LINE);
        appendLine(block, "COMPARE - Day 2 (5 variants)");
        appendLine(block, "User prompt: \"" + prompt + "\"");
        appendLine(block, LINE);
        return block.toString();
    }

    public String logRequest(
            String label,
            String url,
            String model,
            List<Message> messages,
            OpenAiChatOptions options,
            String answer) {
        StringBuilder block = new StringBuilder();
        appendLine(block, "");
        appendLine(block, "=== " + label + " ===");
        appendLine(block, "POST " + url);
        appendLine(block, "");
        appendLine(block, "Request:");
        appendLine(block, toPrettyJson(buildRequestBody(model, messages, options)));
        appendLine(block, "");
        appendLine(block, "Response:");
        appendLine(block, toPrettyJson(buildResponseBody(answer)));
        return block.toString();
    }

    public String logReasoningCompareStart(String prompt) {
        StringBuilder block = new StringBuilder();
        appendLine(block, LINE);
        appendLine(block, "COMPARE - Day 3 (4 reasoning methods)");
        appendLine(block, "User prompt: \"" + prompt + "\"");
        appendLine(block, LINE);
        return block.toString();
    }

    public String logReasoningSummary(
            int directLen,
            int stepByStepLen,
            int metaPromptLen,
            int metaPromptAnswerLen,
            int expertsLen,
            int comparisonLen) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("direct", directLen);
        summary.put("stepByStep", stepByStepLen);
        summary.put("metaPrompt", metaPromptLen);
        summary.put("metaPromptAnswer", metaPromptAnswerLen);
        summary.put("experts", expertsLen);
        summary.put("comparison", comparisonLen);

        StringBuilder block = new StringBuilder();
        appendLine(block, "");
        appendLine(block, "=== SUMMARY (response length in chars) ===");
        appendLine(block, toPrettyJson(summary));
        appendLine(block, LINE);
        appendLine(block, "");
        return block.toString();
    }

    public String logCompareSummary(
            int unrestrictedLen,
            int formatOnlyLen,
            int lengthOnlyLen,
            int stopOnlyLen,
            int fullControlLen) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("unrestricted", unrestrictedLen);
        summary.put("formatOnly", formatOnlyLen);
        summary.put("lengthOnly", lengthOnlyLen);
        summary.put("stopOnly", stopOnlyLen);
        summary.put("fullControl", fullControlLen);

        StringBuilder block = new StringBuilder();
        appendLine(block, "");
        appendLine(block, "=== SUMMARY (response length in chars) ===");
        appendLine(block, toPrettyJson(summary));
        appendLine(block, LINE);
        appendLine(block, "");
        return block.toString();
    }

    private Map<String, Object> buildRequestBody(
            String model,
            List<Message> messages,
            OpenAiChatOptions options) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", toMessageMaps(messages));

        if (options != null && options.getTemperature() != null) {
            body.put("temperature", options.getTemperature());
        }
        if (options != null && options.getMaxTokens() != null) {
            body.put("max_tokens", options.getMaxTokens());
        }
        if (options != null && options.getStopSequences() != null && !options.getStopSequences().isEmpty()) {
            body.put("stop", options.getStopSequences());
        }
        return body;
    }

    private Map<String, Object> buildResponseBody(String answer) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", answer);

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("message", message);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("choices", List.of(choice));
        return body;
    }

    private Map<String, Object> buildResponseBodyWithMetrics(String answer, ModelMetrics metrics) {
        Map<String, Object> body = buildResponseBody(answer);
        body.put("usage", toMetricsMap(metrics));
        return body;
    }

    private Map<String, Object> toMetricsMap(ModelMetrics metrics) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("model", metrics.modelId());
        map.put("responseTimeMs", metrics.responseTimeMs());
        map.put("prompt_tokens", metrics.promptTokens());
        map.put("completion_tokens", metrics.completionTokens());
        map.put("total_tokens", metrics.totalTokens());
        map.put("costUsd", metrics.costUsd());
        return map;
    }

    private List<Map<String, String>> toMessageMaps(List<Message> messages) {
        List<Map<String, String>> result = new ArrayList<>();
        for (Message message : messages) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("role", message.getMessageType().getValue());
            entry.put("content", message.getText());
            result.add(entry);
        }
        return result;
    }

    private String toPrettyJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"error\": \"Failed to serialize JSON\"}";
        }
    }

    private void appendLine(StringBuilder block, String line) {
        block.append(line).append('\n');
        log.info(line);
    }
}
