package com.example.llmchat.memory;

import com.example.llmchat.agent.ContextCompressionService;
import com.example.llmchat.agent.ContextStrategy;
import com.example.llmchat.agent.OpenRouterHttpClient;
import com.example.llmchat.dto.AgentChatMessage;
import com.example.llmchat.dto.MemoryContextSnapshot;
import com.example.llmchat.dto.UserProfileSnapshot;
import com.example.llmchat.dto.TaskStateSnapshot;
import com.example.llmchat.invariants.InvariantCheckResult;
import com.example.llmchat.invariants.InvariantContext;
import com.example.llmchat.invariants.InvariantsService;
import com.example.llmchat.dto.InvariantsSnapshot;
import com.example.llmchat.personalization.PersonalizationService;
import com.example.llmchat.personalization.UserProfile;
import com.example.llmchat.task.TaskState;
import com.example.llmchat.task.TaskStateService;
import com.example.llmchat.task.TaskTransitionService;
import com.example.llmchat.task.TaskTransitionType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class ContextAssembler {

    private static final String AGENT_MCP_SYSTEM_HINT = """
            Режим agent-driven MCP (Day 20): доступны tools серверов mcp-study, mcp-pipeline, mcp-scheduler.
            Если пользователь просит найти материалы, собрать конспект, сохранить файл или поставить напоминание:
            1) searchTopic (study) или search (pipeline) — найти материалы;
            2) summarize (pipeline) — собрать конспект из itemsJson;
            3) saveToFile (pipeline) — сохранить поле summary из summarize, не одну строку из searchTopic;
            4) scheduleReminder (scheduler) — если просили напоминание.
            Учебный Task FSM и инварианты планирования здесь не действуют.""";

    private final MemoryManager memoryManager;
    private final ContextCompressionService contextCompressionService;
    private final PersonalizationService personalizationService;
    private final TaskStateService taskStateService;
    private final TaskTransitionService taskTransitionService;
    private final InvariantsService invariantsService;
    private final String systemPrompt;

    public ContextAssembler(
            MemoryManager memoryManager,
            ContextCompressionService contextCompressionService,
            PersonalizationService personalizationService,
            TaskStateService taskStateService,
            TaskTransitionService taskTransitionService,
            InvariantsService invariantsService,
            @Value("${app.agent.system-prompt}") String systemPrompt) {
        this.memoryManager = memoryManager;
        this.contextCompressionService = contextCompressionService;
        this.personalizationService = personalizationService;
        this.taskStateService = taskStateService;
        this.taskTransitionService = taskTransitionService;
        this.invariantsService = invariantsService;
        this.systemPrompt = systemPrompt;
    }

    public AssembledContext assemble(
            String userId,
            String sessionId,
            String prompt,
            ContextStrategy strategy,
            boolean useDay10Strategy) {
        return assemble(userId, sessionId, prompt, strategy, useDay10Strategy, null);
    }

    public AssembledContext assemble(
            String userId,
            String sessionId,
            String prompt,
            ContextStrategy strategy,
            boolean useDay10Strategy,
            InvariantCheckResult invariantCheck) {
        return assemble(userId, sessionId, prompt, strategy, useDay10Strategy, invariantCheck, false);
    }

    public AssembledContext assemble(
            String userId,
            String sessionId,
            String prompt,
            ContextStrategy strategy,
            boolean useDay10Strategy,
            InvariantCheckResult invariantCheck,
            boolean agentDrivenMcp) {
        MemoryContextSnapshot snapshot = memoryManager.buildContextSnapshot(userId, sessionId, strategy);
        List<String> logs = new ArrayList<>(snapshot.memoryLogs());

        List<OpenRouterHttpClient.ChatMessage> messages = new ArrayList<>();
        messages.add(new OpenRouterHttpClient.ChatMessage("system", systemPrompt));

        UserProfile profile = personalizationService.getProfile(userId);
        String profileBlock = personalizationService.formatProfileBlock(profile);
        boolean profileApplied = profileBlock != null;
        List<String> personalizationLogs = personalizationService.buildPersonalizationLogs(profile, profileApplied);
        if (profileApplied) {
            messages.add(new OpenRouterHttpClient.ChatMessage("system", profileBlock));
        }
        UserProfileSnapshot profileSnapshot = new UserProfileSnapshot(
                profile.displayName(),
                profile.responseStyle(),
                profile.responseFormat(),
                profile.constraints(),
                profileApplied);

        TaskState taskState = agentDrivenMcp ? null : taskStateService.getState(sessionId).orElse(null);
        List<TaskTransitionType> allowedTransitions = agentDrivenMcp
                ? List.of()
                : taskTransitionService.allowedNext(sessionId);
        String taskBlock = agentDrivenMcp ? null : taskStateService.formatTaskBlock(taskState, allowedTransitions);
        boolean taskApplied = taskBlock != null;
        List<String> taskStateLogs = agentDrivenMcp
                ? List.of("TASK → не в контексте (agent-driven MCP)")
                : taskStateService.buildTaskStateLogs(taskState, taskApplied);
        if (taskApplied) {
            messages.add(new OpenRouterHttpClient.ChatMessage("system", taskBlock));
        }
        TaskStateSnapshot taskStateSnapshot = taskStateService.toSnapshot(taskState, taskApplied);

        InvariantContext invariantContext = new InvariantContext(
                userId,
                sessionId,
                prompt,
                Optional.ofNullable(taskState),
                profile);
        String invariantsBlock = agentDrivenMcp
                ? null
                : invariantsService.formatInvariantsBlock(invariantContext, invariantCheck);
        boolean invariantsApplied = invariantsBlock != null;
        List<String> invariantLogs = agentDrivenMcp
                ? List.of("INVARIANTS → не в контексте (agent-driven MCP)")
                : invariantsService.buildInvariantsLogs(
                        invariantContext, invariantsApplied, invariantCheck);
        InvariantsSnapshot invariantsSnapshot = agentDrivenMcp
                ? new InvariantsSnapshot(0, List.of(), false, List.of())
                : invariantsService.toSnapshot(
                        invariantContext, invariantsApplied, invariantCheck);
        if (invariantsApplied) {
            messages.add(new OpenRouterHttpClient.ChatMessage("system", invariantsBlock));
        }
        if (agentDrivenMcp) {
            messages.add(new OpenRouterHttpClient.ChatMessage("system", AGENT_MCP_SYSTEM_HINT));
        }

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
                snapshot.workingSummaryInContext(),
                profileBlock,
                profileSnapshot,
                personalizationLogs,
                taskBlock,
                taskStateSnapshot,
                taskStateLogs,
                invariantsBlock,
                invariantsSnapshot,
                invariantLogs);
    }

    public record AssembledContext(
            List<OpenRouterHttpClient.ChatMessage> messages,
            MemoryContextSnapshot memorySnapshot,
            List<String> memoryLogs,
            int messagesInContext,
            String factsBlock,
            String summary,
            String profileBlock,
            UserProfileSnapshot profileSnapshot,
            List<String> personalizationLogs,
            String taskBlock,
            TaskStateSnapshot taskStateSnapshot,
            List<String> taskStateLogs,
            String invariantsBlock,
            InvariantsSnapshot invariantsSnapshot,
            List<String> invariantLogs) {
    }
}
