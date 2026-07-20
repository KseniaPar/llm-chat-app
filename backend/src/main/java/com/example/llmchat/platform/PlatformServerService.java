package com.example.llmchat.platform;

import com.example.llmchat.config.LlmProviderConfig;
import com.example.llmchat.dto.LocalLlmServiceVerifyCheckDto;
import com.example.llmchat.dto.LocalLlmStatusResponse;
import com.example.llmchat.dto.PlatformInfoResponse;
import com.example.llmchat.dto.PlatformModuleDto;
import com.example.llmchat.dto.PlatformVerifyResponse;
import com.example.llmchat.localllm.LocalLlmPrivateService;
import com.example.llmchat.localllm.LocalLlmService;
import com.example.llmchat.mcp.McpConnectionService;
import com.example.llmchat.rag.ChunkingStrategy;
import com.example.llmchat.rag.RagChatService;
import com.example.llmchat.rag.RagIndexStore;
import com.example.llmchat.rag.RagLlmProvider;
import com.example.llmchat.rag.RagQueryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class PlatformServerService {

    private final LocalLlmService localLlmService;
    private final LocalLlmPrivateService privateService;
    private final RagIndexStore ragIndexStore;
    private final RagQueryService ragQueryService;
    private final RagChatService ragChatService;
    private final McpConnectionService mcpConnectionService;
    private final LlmProviderConfig llmProviderConfig;
    private final Environment environment;
    private final String platformName;
    private final String platformDescription;
    private final boolean mcpEnabled;
    private final String openRouterApiKey;

    public PlatformServerService(
            LocalLlmService localLlmService,
            LocalLlmPrivateService privateService,
            RagIndexStore ragIndexStore,
            RagQueryService ragQueryService,
            RagChatService ragChatService,
            McpConnectionService mcpConnectionService,
            LlmProviderConfig llmProviderConfig,
            Environment environment,
            @Value("${app.platform.name:Учебный AI-сервер}") String platformName,
            @Value("${app.platform.description:Приватная образовательная платформа: RAG, локальная LLM, память агента, MCP.}") String platformDescription,
            @Value("${app.mcp.enabled:false}") boolean mcpEnabled,
            @Value("${app.openrouter.api-key:}") String openRouterApiKey) {
        this.localLlmService = localLlmService;
        this.privateService = privateService;
        this.ragIndexStore = ragIndexStore;
        this.ragQueryService = ragQueryService;
        this.ragChatService = ragChatService;
        this.mcpConnectionService = mcpConnectionService;
        this.llmProviderConfig = llmProviderConfig;
        this.environment = environment;
        this.platformName = platformName;
        this.platformDescription = platformDescription;
        this.mcpEnabled = mcpEnabled;
        this.openRouterApiKey = openRouterApiKey;
    }

    public PlatformInfoResponse info() {
        LocalLlmStatusResponse llmStatus = localLlmService.checkStatus();
        boolean llmReady = llmStatus.online() && llmStatus.modelAvailable();
        int localChunks = ragIndexStore.local().countChunks(ChunkingStrategy.STRUCTURE);
        boolean mcpReady = !mcpEnabled || mcpConnectionService.getStatus().connected();
        boolean localOnly = llmProviderConfig.isLocal();

        List<PlatformModuleDto> modules = List.of(
                module(
                        "private-llm",
                        "Приватный LLM-сервис",
                        "HTTP API к Ollama с rate limit и max context (Day 30)",
                        true,
                        llmStatus.online() && llmStatus.modelAvailable(),
                        llmStatus.message(),
                        "/api/local-llm/service/info",
                        "/api/local-llm/service/chat",
                        "/api/local-llm/service/verify"),
                module(
                        "rag-local",
                        "Локальный RAG",
                        "Поиск по корпусу «Основы православия» + Ollama chat (Days 21–28)",
                        true,
                        llmStatus.online() && localChunks > 0,
                        localChunks > 0
                                ? "Индекс: " + localChunks + " чанков (STRUCTURE)"
                                : "Локальный индекс пуст — выполните POST /api/rag/local/index",
                        "/api/rag/chat",
                        "/api/rag/local/index",
                        "/api/rag/local/index/status"),
                module(
                        "agent",
                        "Stateful-агент",
                        "Память 3 слоя, FSM задач, инварианты, переходы (Days 11–15)",
                        true,
                        llmReady,
                        llmReady
                                ? (localOnly
                                        ? "Локальный LLM (Ollama) — /api/agent/chat"
                                        : "OpenRouter — /api/agent/chat")
                                : "Ollama недоступен для агента",
                        "/api/agent/chat",
                        "/api/agent/memory",
                        "/api/agent/task"),
                module(
                        "auth",
                        "Аутентификация",
                        "JWT регистрация/логин для агента и профиля (Day 11–12)",
                        true,
                        true,
                        "POST /api/auth/register, /api/auth/login",
                        "/api/auth/register",
                        "/api/auth/login",
                        "/api/user/profile"),
                module(
                        "mcp",
                        "MCP-оркестрация",
                        "Study, Pipeline, Scheduler (Days 16–20)",
                        mcpEnabled,
                        mcpReady,
                        mcpEnabled
                                ? (mcpReady ? "MCP online" : "MCP offline")
                                : "Отключено (app.mcp.enabled=false)",
                        "/api/mcp/status",
                        "/api/mcp/orchestration/run",
                        "/api/mcp/pipeline/run"),
                module(
                        "personalization",
                        "Профиль студента",
                        "Стиль, формат, ограничения ответов (Day 12)",
                        true,
                        true,
                        "GET/PUT /api/user/profile",
                        "/api/user/profile"));

        boolean ready = modules.stream()
                .filter(module -> "private-llm".equals(module.id()) || "rag-local".equals(module.id()))
                .allMatch(PlatformModuleDto::ready);

        String message = ready
                ? (localOnly
                        ? "Платформа готова: полностью локально на Ollama (RAG + агент + LLM API)."
                        : "Платформа готова: локальный RAG + приватный LLM на Ollama.")
                : "Платформа частично готова — проверьте Ollama и локальный RAG-индекс.";

        return new PlatformInfoResponse(
                platformName,
                platformDescription,
                "day32-platform",
                activeProfiles(),
                ready,
                message,
                modules);
    }

    public PlatformVerifyResponse verify() {
        List<LocalLlmServiceVerifyCheckDto> checks = new ArrayList<>();
        checks.addAll(privateService.verify().checks());

        long started = System.currentTimeMillis();
        try {
            int chunks = ragIndexStore.local().countChunks(ChunkingStrategy.STRUCTURE);
            boolean passed = chunks > 0;
            checks.add(new LocalLlmServiceVerifyCheckDto(
                    "rag-index",
                    "Локальный RAG-индекс",
                    passed,
                    System.currentTimeMillis() - started,
                    passed ? chunks + " чанков STRUCTURE" : "Индекс пуст"));
        } catch (Exception exception) {
            checks.add(new LocalLlmServiceVerifyCheckDto(
                    "rag-index",
                    "Локальный RAG-индекс",
                    false,
                    System.currentTimeMillis() - started,
                    exception.getMessage()));
        }

        started = System.currentTimeMillis();
        try {
            var response = ragQueryService.query(
                    "Сколько таинств в Православной Церкви? Ответ одним числом.",
                    true,
                    ChunkingStrategy.STRUCTURE,
                    5,
                    null,
                    null,
                    RagLlmProvider.LOCAL);
            boolean passed = response.answer() != null && !response.answer().isBlank();
            checks.add(new LocalLlmServiceVerifyCheckDto(
                    "rag-query",
                    "RAG-запрос (LOCAL)",
                    passed,
                    System.currentTimeMillis() - started,
                    passed ? truncate(response.answer(), 80) : "Пустой ответ"));
        } catch (Exception exception) {
            checks.add(new LocalLlmServiceVerifyCheckDto(
                    "rag-query",
                    "RAG-запрос (LOCAL)",
                    false,
                    System.currentTimeMillis() - started,
                    exception.getMessage()));
        }

        started = System.currentTimeMillis();
        try {
            var chat = ragChatService.chat(new com.example.llmchat.dto.RagChatRequest(
                    null,
                    "Что такое Крещение? Одно предложение.",
                    ChunkingStrategy.STRUCTURE,
                    5,
                    null,
                    null,
                    RagLlmProvider.LOCAL));
            boolean passed = chat.assistantMessage() != null
                    && chat.assistantMessage().content() != null
                    && !chat.assistantMessage().content().isBlank();
            checks.add(new LocalLlmServiceVerifyCheckDto(
                    "rag-chat",
                    "RAG-чат (LOCAL)",
                    passed,
                    System.currentTimeMillis() - started,
                    passed ? truncate(chat.assistantMessage().content(), 80) : "Пустой ответ"));
        } catch (Exception exception) {
            checks.add(new LocalLlmServiceVerifyCheckDto(
                    "rag-chat",
                    "RAG-чат (LOCAL)",
                    false,
                    System.currentTimeMillis() - started,
                    exception.getMessage()));
        }

        if (mcpEnabled) {
            started = System.currentTimeMillis();
            boolean passed = mcpConnectionService.getStatus().connected();
            checks.add(new LocalLlmServiceVerifyCheckDto(
                    "mcp",
                    "MCP-подключение",
                    passed,
                    System.currentTimeMillis() - started,
                    passed ? "MCP online" : "MCP offline"));
        }

        boolean allPassed = checks.stream().allMatch(LocalLlmServiceVerifyCheckDto::passed);
        long failed = checks.stream().filter(check -> !check.passed()).count();
        String summary = allPassed
                ? "Платформа проверена: Ollama, RAG, приватный API, чат."
                : "Провалено: " + failed + " из " + checks.size() + " проверок.";

        return new PlatformVerifyResponse(allPassed, List.copyOf(checks), summary);
    }

    private PlatformModuleDto module(
            String id,
            String name,
            String description,
            boolean enabled,
            boolean ready,
            String status,
            String... endpoints) {
        return new PlatformModuleDto(id, name, description, enabled, ready, status, List.of(endpoints));
    }

    private boolean isCloudConfigured() {
        return openRouterApiKey != null
                && !openRouterApiKey.isBlank()
                && !"local-llm-not-used".equals(openRouterApiKey);
    }

    private String activeProfiles() {
        String[] profiles = environment.getActiveProfiles();
        return profiles.length == 0 ? "default" : String.join(",", profiles);
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength) + "…";
    }
}
