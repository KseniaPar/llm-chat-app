package com.example.llmchat.mcp;

import com.example.mcp.pipeline.PipelineCorpus;
import com.example.mcp.study.StudyTopicsSeed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Replaces corrupted MCP STDIO Cyrillic with canonical local corpus/seed text for orchestration UI and chains.
 */
final class McpOrchestrationResultRepair {

    private McpOrchestrationResultRepair() {
    }

    static String repairStudySearch(String rawJson, String query, String subject, ObjectMapper objectMapper) {
        try {
            JsonNode root = objectMapper.readTree(rawJson != null && !rawJson.isBlank() ? rawJson : "{}");
            List<StudyTopicsSeed.TopicSeed> localMatches = StudyTopicsSeed.searchLocal(query, subject);
            if (localMatches.isEmpty() && subject != null && !subject.isBlank()) {
                localMatches = StudyTopicsSeed.searchLocal(query, null);
            }
            if (localMatches.isEmpty()) {
                return rawJson;
            }
            ObjectNode repaired = root.isObject() ? (ObjectNode) root.deepCopy() : objectMapper.createObjectNode();
            ArrayNode matches = objectMapper.createArrayNode();
            for (StudyTopicsSeed.TopicSeed seed : localMatches) {
                ObjectNode match = objectMapper.createObjectNode();
                match.put("subject", seed.subject());
                match.put("topic", seed.topic());
                match.put("summary", seed.summary());
                match.put("examHints", seed.examHints());
                matches.add(match);
            }
            repaired.set("matches", matches);
            repaired.put("matchCount", matches.size());
            if (query != null) {
                repaired.put("query", query);
            }
            if (subject != null && !subject.isBlank()) {
                repaired.put("subjectFilter", subject);
            }
            return objectMapper.writeValueAsString(repaired);
        } catch (Exception exception) {
            return rawJson;
        }
    }

    static String repairPipelineSearch(String rawJson, String query, ObjectMapper objectMapper) {
        try {
            JsonNode root = objectMapper.readTree(rawJson != null && !rawJson.isBlank() ? rawJson : "{}");
            List<Map<String, Object>> localItems = new ArrayList<>();
            JsonNode itemsNode = root.path("items");
            if (itemsNode.isArray() && !itemsNode.isEmpty()) {
                for (JsonNode item : itemsNode) {
                    String url = item.path("url").asText("");
                    PipelineCorpus.itemByUrl(url).ifPresent(localItems::add);
                }
            }
            if (localItems.isEmpty()) {
                localItems.addAll(PipelineCorpus.searchPublic(query != null ? query : ""));
            }
            if (localItems.isEmpty()) {
                return rawJson;
            }
            ObjectNode repaired = root.isObject() ? (ObjectNode) root.deepCopy() : objectMapper.createObjectNode();
            repaired.set("items", objectMapper.valueToTree(localItems));
            repaired.put("itemCount", localItems.size());
            if (query != null) {
                repaired.put("query", query);
            }
            return objectMapper.writeValueAsString(repaired);
        } catch (Exception exception) {
            return rawJson;
        }
    }

    static String repairSummarize(String rawJson, String itemsJson, ObjectMapper objectMapper) {
        try {
            JsonNode items = objectMapper.readTree(itemsJson != null && !itemsJson.isBlank() ? itemsJson : "[]");
            if (!items.isArray() || items.isEmpty()) {
                return rawJson;
            }
            StringBuilder summary = new StringBuilder();
            List<String> keyPoints = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                JsonNode item = items.get(i);
                String title = item.path("title").asText("");
                String snippet = item.path("snippet").asText("");
                if (!title.isBlank()) {
                    summary.append(i + 1).append(". ").append(title).append(" — ");
                    keyPoints.add(title);
                }
                summary.append(snippet);
                if (!snippet.endsWith(".")) {
                    summary.append('.');
                }
                summary.append('\n');
                if (keyPoints.size() < 5 && !snippet.isBlank()) {
                    keyPoints.add(snippet.length() > 90 ? snippet.substring(0, 87) + "…" : snippet);
                }
            }
            ObjectNode repaired = objectMapper.createObjectNode();
            repaired.put("summary", summary.toString().trim());
            repaired.set("keyPoints", objectMapper.valueToTree(keyPoints.size() > 5 ? keyPoints.subList(0, 5) : keyPoints));
            repaired.put("sourceCount", items.size());
            return objectMapper.writeValueAsString(repaired);
        } catch (Exception exception) {
            return rawJson;
        }
    }

    static boolean needsRepair(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (McpTextEncoding.looksLikeMojibake(text)) {
            return true;
        }
        int boxDrawing = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= '\u2500' && ch <= '\u257F') {
                boxDrawing++;
            }
        }
        return boxDrawing >= 2;
    }
}
