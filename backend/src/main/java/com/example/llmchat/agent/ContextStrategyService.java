package com.example.llmchat.agent;

import com.example.llmchat.dto.AgentChatMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ContextStrategyService {

    private final ConversationStore conversationStore;
    private final FactsMemoryService factsMemoryService;
    private final BranchingService branchingService;
    private final int windowSize;

    public ContextStrategyService(
            ConversationStore conversationStore,
            FactsMemoryService factsMemoryService,
            BranchingService branchingService,
            @Value("${app.agent.context.window-size}") int windowSize) {
        this.conversationStore = conversationStore;
        this.factsMemoryService = factsMemoryService;
        this.branchingService = branchingService;
        this.windowSize = windowSize;
    }

    public int windowSize() {
        return windowSize;
    }

    public PreparedContext prepareContext(String sessionId, ContextStrategy strategy) {
        if (strategy == null) {
            List<AgentChatMessage> history = conversationStore.getEffectiveHistory(sessionId);
            return new PreparedContext(history, null, 0, history.size(), conversationStore.getStoredMessageCount(sessionId));
        }

        return switch (strategy) {
            case SLIDING_WINDOW -> {
                List<AgentChatMessage> window = conversationStore.getWindowForContext(sessionId, windowSize);
                int stored = conversationStore.getStoredMessageCount(sessionId);
                yield new PreparedContext(window, null, Math.max(0, stored - window.size()), window.size(), stored);
            }
            case STICKY_FACTS -> {
                List<AgentChatMessage> window = conversationStore.getWindowForContext(sessionId, windowSize);
                Map<String, String> facts = conversationStore.getFacts(sessionId);
                String factsBlock = factsMemoryService.formatFactsForContext(facts);
                int stored = conversationStore.getStoredMessageCount(sessionId);
                yield new PreparedContext(window, factsBlock, Math.max(0, stored - window.size()), window.size(), stored);
            }
            case BRANCHING -> {
                List<AgentChatMessage> context = branchingService.getBranchingContext(sessionId, windowSize);
                int stored = conversationStore.getStoredMessageCount(branchingService.resolveActiveSessionId(sessionId));
                yield new PreparedContext(context, null, 0, context.size(), stored);
            }
        };
    }

    public Map<String, String> afterUserMessage(String sessionId, ContextStrategy strategy, String userPrompt) {
        if (strategy == null) {
            return Map.of();
        }

        return switch (strategy) {
            case SLIDING_WINDOW -> {
                conversationStore.trimToWindow(sessionId, windowSize);
                yield Map.of();
            }
            case STICKY_FACTS -> factsMemoryService.updateFacts(sessionId, userPrompt);
            case BRANCHING -> Map.of();
        };
    }

    public void afterAssistantMessage(String sessionId, ContextStrategy strategy) {
        if (strategy == ContextStrategy.SLIDING_WINDOW) {
            conversationStore.trimToWindow(sessionId, windowSize);
        }
    }

    public void ensureStrategy(String sessionId, ContextStrategy strategy) {
        if (strategy == null) {
            return;
        }
        ContextStrategy current = conversationStore.getContextStrategy(sessionId);
        if (current == null) {
            conversationStore.setContextStrategy(sessionId, strategy);
        }
    }

    public record PreparedContext(
            List<AgentChatMessage> messages,
            String factsBlock,
            int messagesDropped,
            int messagesInContext,
            int messagesInStore) {
    }
}
