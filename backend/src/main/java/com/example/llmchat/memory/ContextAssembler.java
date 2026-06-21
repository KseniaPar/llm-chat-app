package com.example.llmchat.memory;

import com.example.llmchat.agent.ContextCompressionService;
import com.example.llmchat.agent.ContextStrategy;
import com.example.llmchat.agent.OpenRouterHttpClient;
import com.example.llmchat.dto.AgentChatMessage;
import com.example.llmchat.dto.MemoryContextSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ContextAssembler {

    private final MemoryManager memoryManager;
    private final ContextCompressionService contextCompressionService;
    private final String systemPrompt;

    public ContextAssembler(
            MemoryManager memoryManager,
            ContextCompressionService contextCompressionService,
            @Value("${app.agent.system-prompt}") String systemPrompt) {
        this.memoryManager = memoryManager;
        this.contextCompressionService = contextCompressionService;
        this.systemPrompt = systemPrompt;
    }

    public AssembledContext assemble(
            String userId,
            String sessionId,
            String prompt,
            ContextStrategy strategy,
            boolean useDay10Strategy) {
        MemoryContextSnapshot snapshot = memoryManager.buildContextSnapshot(userId, sessionId, strategy);
        List<String> logs = new ArrayList<>(snapshot.memoryLogs());

        List<OpenRouterHttpClient.ChatMessage> messages = new ArrayList<>();
        messages.add(new OpenRouterHttpClient.ChatMessage("system", systemPrompt));

        String longTermBlock = memoryManager.formatLongTermBlock(snapshot.longTermInContext());
        if (longTermBlock != null) {
            messages.add(new OpenRouterHttpClient.ChatMessage("system", longTermBlock));
            logs.add("В промпт добавлен LONG-блок");
        }

        if (!useDay10Strategy) {
            String summaryBlock = memoryManager.formatWorkingSummaryBlock(snapshot.workingSummaryInContext());
            if (summaryBlock == null) {
                summaryBlock = contextCompressionService.formatSummaryForContext(snapshot.workingSummaryInContext());
            }
            if (summaryBlock != null) {
                messages.add(new OpenRouterHttpClient.ChatMessage("system", summaryBlock));
                logs.add("В промпт добавлен WORKING summary");
            }
        } else {
            String summaryBlock = memoryManager.formatWorkingSummaryBlock(snapshot.workingSummaryInContext());
            if (summaryBlock != null) {
                messages.add(new OpenRouterHttpClient.ChatMessage("system", summaryBlock));
                logs.add("В промпт добавлен WORKING summary");
            }
        }

        String factsBlock = memoryManager.formatWorkingFactsBlock(snapshot.workingFactsInContext());
        if (factsBlock != null) {
            messages.add(new OpenRouterHttpClient.ChatMessage("system", factsBlock));
            logs.add("В промпт добавлен WORKING facts (" + snapshot.workingFactsInContext().size() + ")");
        }

        for (AgentChatMessage entry : snapshot.shortTermInContext()) {
            messages.add(new OpenRouterHttpClient.ChatMessage(entry.role(), entry.content()));
        }
        messages.add(new OpenRouterHttpClient.ChatMessage("user", prompt));

        return new AssembledContext(
                messages,
                snapshot,
                logs,
                snapshot.shortTermInContext().size(),
                factsBlock,
                snapshot.workingSummaryInContext());
    }

    public record AssembledContext(
            List<OpenRouterHttpClient.ChatMessage> messages,
            MemoryContextSnapshot memorySnapshot,
            List<String> memoryLogs,
            int messagesInContext,
            String factsBlock,
            String summary) {
    }
}
