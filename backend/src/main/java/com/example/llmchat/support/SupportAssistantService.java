package com.example.llmchat.support;

import com.example.llmchat.config.AgentChatClientConfig;
import com.example.llmchat.dto.McpToolCallLogDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Day 33 — support assistant: FAQ RAG + MCP JSON tickets.
 */
@Service
public class SupportAssistantService {

    private static final Logger log = LoggerFactory.getLogger(SupportAssistantService.class);

    private static final String PLACEHOLDER_KEY = "local-llm-not-used";

    private static final String SYSTEM_PROMPT = """
            Ты ассистент поддержки пользователей продукта llm-chat-app.
            Отвечай на русском, спокойно и по шагам.

            В сообщении уже могут быть: данные тикета (MCP) и фрагменты FAQ.
            Сначала опирайся на них. Инструменты вызывай только если нужно уточнить
            другой тикет или дополнительно поискать FAQ:
            • retrieveSupportFaq
            • listTickets / getTicket / createTicket / updateTicketStatus

            Статусы тикета: open → in_progress|resolved; in_progress → open|resolved; resolved → open|in_progress.
            Не путай учебный чат о православии с поддержкой продукта.
            Если данных мало — скажи, каких логов/шагов не хватает.
            """;

    private final ChatClient supportChatClient;
    private final ToolCallback[] supportToolCallbacks;
    private final SupportFaqTools faqTools;
    private final TicketsMcpFacade ticketsMcpFacade;
    private final String apiKey;
    private final String openRouterModel;
    private final double temperature;
    private final int maxTokens;

    public SupportAssistantService(
            @Qualifier("supportChatClient") ChatClient supportChatClient,
            @Qualifier("supportToolCallbacks") ToolCallback[] supportToolCallbacks,
            SupportFaqTools faqTools,
            TicketsMcpFacade ticketsMcpFacade,
            @Value("${app.openrouter.api-key:}") String apiKey,
            @Value("${app.openrouter.model}") String openRouterModel,
            @Value("${app.agent.temperature:0.5}") double temperature,
            @Value("${app.support.max-tokens:1200}") int maxTokens) {
        this.supportChatClient = supportChatClient;
        this.supportToolCallbacks = supportToolCallbacks;
        this.faqTools = faqTools;
        this.ticketsMcpFacade = ticketsMcpFacade;
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.openRouterModel = openRouterModel;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    public boolean cloudConfigured() {
        return !apiKey.isBlank() && !PLACEHOLDER_KEY.equals(apiKey);
    }

    public SupportAnswer answer(String question, String ticketId) {
        long started = System.currentTimeMillis();
        String q = question != null ? question.trim() : "";
        if (q.isBlank()) {
            throw new IllegalArgumentException("question is required");
        }
        if (!cloudConfigured()) {
            throw new IllegalStateException("OPENROUTER_API_KEY не задан — ассистент поддержки недоступен.");
        }

        AgentChatClientConfig.beginToolCallRecording();
        SupportFaqTools.beginSourceRecording();

        List<McpToolCallLogDto> prefetched = new ArrayList<>();
        long prefetchStarted = System.currentTimeMillis();

        String faqContext = faqTools.retrieveSupportFaq(q);
        prefetched.add(new McpToolCallLogDto(
                SupportFaqTools.TOOL_SERVER_NAME,
                "retrieveSupportFaq",
                "{\"query\":\"" + q.replace("\"", "'") + "\"}",
                faqContext != null && faqContext.length() > 200
                        ? faqContext.substring(0, 200) + "…"
                        : faqContext,
                System.currentTimeMillis() - prefetchStarted));

        String ticketContext = "(тикет не выбран)";
        String effectiveTicketId = ticketId != null ? ticketId.trim() : "";
        if (!effectiveTicketId.isBlank()) {
            TicketsMcpFacade.ToolResult ticket = ticketsMcpFacade.getTicket(effectiveTicketId);
            prefetched.add(ticket.toolCall());
            ticketContext = ticket.ok()
                    ? ticket.text()
                    : "(тикет недоступен: " + ticket.text() + ")";
        } else {
            TicketsMcpFacade.ToolResult listed = ticketsMcpFacade.listTickets("open", 10);
            prefetched.add(listed.toolCall());
            ticketContext = "Открытые тикеты (кратко):\n" + listed.text();
        }

        String userMessage = """
                Вопрос пользователя:
                %s

                Ticket id: %s

                --- Тикет / список тикетов (MCP mcp-tickets) ---
                %s

                --- FAQ поддержки (уже загружено) ---
                %s
                """.formatted(
                q,
                effectiveTicketId.isBlank() ? "(не указан)" : effectiveTicketId,
                ticketContext,
                faqContext == null || faqContext.isBlank() ? "(FAQ пуст)" : faqContext);

        String answerText;
        String model = openRouterModel;
        try {
            ChatOptions options = OpenAiChatOptions.builder()
                    .model(openRouterModel)
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .build();

            ChatResponse chatResponse = supportChatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userMessage)
                    .toolCallbacks(supportToolCallbacks)
                    .options(options)
                    .call()
                    .chatResponse();

            answerText = chatResponse != null && chatResponse.getResult() != null
                    ? chatResponse.getResult().getOutput().getText()
                    : "";
            if (answerText == null || answerText.isBlank()) {
                answerText = "Не удалось сформировать ответ. Проверьте FAQ-индекс и mcp-tickets.";
            } else {
                answerText = answerText.trim();
            }
            if (chatResponse != null
                    && chatResponse.getMetadata() != null
                    && chatResponse.getMetadata().getModel() != null) {
                model = chatResponse.getMetadata().getModel();
            }
        } catch (Exception exception) {
            log.warn("Support assistant failed: {}", exception.getMessage());
            answerText = "Ошибка ассистента поддержки: " + exception.getMessage();
        }

        List<McpToolCallLogDto> extra = AgentChatClientConfig.drainToolCallRecording();
        List<String> sources = SupportFaqTools.drainSources();
        List<McpToolCallLogDto> allTools = new ArrayList<>(prefetched);
        allTools.addAll(extra);

        long durationMs = System.currentTimeMillis() - started;
        log.info("Support answered in {} ms, ticket={}, sources={}, tools={}",
                durationMs, effectiveTicketId, sources.size(), allTools.size());
        return new SupportAnswer(
                answerText,
                model,
                durationMs,
                List.copyOf(sources),
                List.copyOf(allTools),
                effectiveTicketId.isBlank() ? null : effectiveTicketId);
    }

    public record SupportAnswer(
            String answer,
            String model,
            long durationMs,
            List<String> sources,
            List<McpToolCallLogDto> mcpToolCalls,
            String ticketId) {
    }
}
