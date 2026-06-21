package com.example.llmchat.memory;

import com.example.llmchat.agent.ContextStrategy;
import com.example.llmchat.agent.FactsMemoryService;
import com.example.llmchat.agent.SessionState;
import com.example.llmchat.dto.AgentChatMessage;
import com.example.llmchat.dto.MemoryContextSnapshot;
import com.example.llmchat.dto.MemoryLayerSnapshot;
import com.example.llmchat.dto.MemorySnapshotResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MemoryManager {

    private final ShortTermMemoryRepository shortTermMemoryRepository;
    private final WorkingMemoryRepository workingMemoryRepository;
    private final LongTermMemoryRepository longTermMemoryRepository;
    private final SqliteSessionRepository sqliteSessionRepository;
    private final MemoryRoutingPolicy routingPolicy;
    private final MemoryExtractorService memoryExtractorService;
    private final int windowSize;

    public MemoryManager(
            ShortTermMemoryRepository shortTermMemoryRepository,
            WorkingMemoryRepository workingMemoryRepository,
            LongTermMemoryRepository longTermMemoryRepository,
            SqliteSessionRepository sqliteSessionRepository,
            MemoryRoutingPolicy routingPolicy,
            MemoryExtractorService memoryExtractorService,
            @Value("${app.agent.context.window-size}") int windowSize) {
        this.shortTermMemoryRepository = shortTermMemoryRepository;
        this.workingMemoryRepository = workingMemoryRepository;
        this.longTermMemoryRepository = longTermMemoryRepository;
        this.sqliteSessionRepository = sqliteSessionRepository;
        this.routingPolicy = routingPolicy;
        this.memoryExtractorService = memoryExtractorService;
        this.windowSize = windowSize;
    }

    public void syncLayers(String sessionId, String userId, SessionState state) {
        sqliteSessionRepository.upsert(sessionId, userId, state);
        shortTermMemoryRepository.replaceAll(sessionId, routingPolicy.routeShortTerm(state));
        workingMemoryRepository.replaceFacts(sessionId, state.getFacts());
        workingMemoryRepository.upsertSummary(sessionId, state.getSummary());
    }

    public MemoryContextSnapshot buildContextSnapshot(String userId, String sessionId, ContextStrategy strategy) {
        List<String> logs = new ArrayList<>();
        List<AgentChatMessage> shortTerm = shortTermMemoryRepository.findContextMessages(sessionId, windowSize);
        Map<String, String> workingFacts = workingMemoryRepository.findFacts(sessionId);
        String workingSummary = workingMemoryRepository.findSummary(sessionId);
        Map<String, Map<String, String>> longTerm = longTermMemoryRepository.findGroupedByCategory(userId);

        logs.add("SHORT: в контексте " + shortTerm.size() + " сообщений (окно " + windowSize + ")");
        logs.add("WORKING: фактов " + workingFacts.size()
                + (workingSummary != null && !workingSummary.isBlank() ? ", summary есть" : ", summary нет"));
        int longEntries = longTerm.values().stream().mapToInt(Map::size).sum();
        logs.add("LONG: записей " + longEntries + " для пользователя");

        if (strategy != null) {
            logs.add("Политика краткосрочной памяти: " + strategy.name());
        }

        return new MemoryContextSnapshot(shortTerm, workingFacts, workingSummary, longTerm, logs);
    }

    public MemorySnapshotResponse getMemorySnapshot(String userId, String sessionId) {
        List<AgentChatMessage> allShort = shortTermMemoryRepository.findAll(sessionId).stream()
                .map(message -> new AgentChatMessage(message.role(), message.content()))
                .toList();
        Map<String, String> workingFacts = workingMemoryRepository.findFacts(sessionId);
        String workingSummary = workingMemoryRepository.findSummary(sessionId);
        Map<String, Map<String, String>> longTerm = longTermMemoryRepository.findGroupedByCategory(userId);

        MemoryLayerSnapshot shortSnapshot = new MemoryLayerSnapshot("SHORT", allShort, Map.of(), null, Map.of());
        MemoryLayerSnapshot workingSnapshot = new MemoryLayerSnapshot(
                "WORKING", List.of(), workingFacts, workingSummary, Map.of());
        MemoryLayerSnapshot longSnapshot = new MemoryLayerSnapshot("LONG", List.of(), Map.of(), null, longTerm);

        List<String> logs = List.of(
                "SHORT: всего " + allShort.size() + " сообщений в хранилище",
                "WORKING: " + workingFacts.size() + " фактов",
                "LONG: " + longTerm.values().stream().mapToInt(Map::size).sum() + " записей");

        return new MemorySnapshotResponse(sessionId, shortSnapshot, workingSnapshot, longSnapshot, logs);
    }

    public void syncWorkingFacts(String sessionId, Map<String, String> facts) {
        workingMemoryRepository.replaceFacts(sessionId, facts);
    }

    public void updateWorkingSummary(String sessionId, String summary) {
        workingMemoryRepository.upsertSummary(sessionId, summary);
    }

    public List<String> extractAndStoreLongTerm(
            String userId,
            String sessionId,
            String userMessage,
            List<AgentChatMessage> recentMessages) {
        List<String> logs = new ArrayList<>();
        Map<String, Map<String, String>> existing = longTermMemoryRepository.findGroupedByCategory(userId);
        MemoryExtractorService.LongTermExtraction extraction =
                memoryExtractorService.extractLongTerm(existing, recentMessages, userMessage);

        if (extraction.isEmpty()) {
            logs.add("LONG: новых записей не извлечено");
            return logs;
        }

        for (Map.Entry<String, Map<String, String>> category : extraction.categories().entrySet()) {
            longTermMemoryRepository.upsertCategory(userId, category.getKey(), category.getValue(), sessionId);
            for (Map.Entry<String, String> entry : category.getValue().entrySet()) {
                logs.add("LONG → " + category.getKey() + "/" + entry.getKey() + ": " + entry.getValue());
            }
        }
        return logs;
    }

    public String formatWorkingFactsBlock(Map<String, String> facts) {
        if (facts == null || facts.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder("Известные факты:\n");
        for (Map.Entry<String, String> entry : facts.entrySet()) {
            builder.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        return builder.toString().trim();
    }

    public String formatLongTermBlock(Map<String, Map<String, String>> longTerm) {
        if (longTerm == null || longTerm.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder("Долговременная память:\n");
        for (Map.Entry<String, Map<String, String>> category : longTerm.entrySet()) {
            builder.append(category.getKey()).append(":\n");
            for (Map.Entry<String, String> entry : category.getValue().entrySet()) {
                builder.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }
        return builder.toString().trim();
    }

    public String formatWorkingSummaryBlock(String summary) {
        if (summary == null || summary.isBlank()) {
            return null;
        }
        return "Резюме задачи:\n" + summary.trim();
    }

    public boolean sessionBelongsToUser(String sessionId, String userId) {
        return sqliteSessionRepository.belongsToUser(sessionId, userId);
    }

    public int windowSize() {
        return windowSize;
    }
}
