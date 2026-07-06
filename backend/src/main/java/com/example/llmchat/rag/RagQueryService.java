package com.example.llmchat.rag;

import com.example.llmchat.agent.CompletionResult;
import com.example.llmchat.agent.OpenRouterHttpClient;
import com.example.llmchat.dto.RagChatMessageDto;
import com.example.llmchat.dto.RagDialogMemoryDto;
import com.example.llmchat.dto.RagModeCompareResponse;
import com.example.llmchat.dto.RagModeResultDto;
import com.example.llmchat.dto.RagQueryCompareResponse;
import com.example.llmchat.dto.RagQueryResponse;
import com.example.llmchat.dto.RagRetrievalMetaDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RagQueryService {

    private static final String RAG_SYSTEM_PROMPT = """
            Ты учебный ассистент по материалу «Основы православия».
            Отвечай ТОЛЬКО на основе предоставленного контекста из документа.
            Ответ должен опираться на переданные фрагменты — не добавляй факты вне контекста.
            Отвечай кратко и по делу на русском языке.
            Источники и цитаты будут добавлены системой автоматически — не перечисляй их в ответе.""";

    private static final String RAG_CHAT_SYSTEM_PROMPT = """
            Ты учебный ассистент по материалу «Основы православия» в мультитurn-диалоге.
            Учитывай историю беседы и цель диалога студента.
            Отвечай ТОЛЬКО на основе предоставленного контекста из документа.
            Не добавляй факты вне контекста. Отвечай кратко и по делу на русском языке.
            Источники и цитаты будут добавлены системой автоматически — не перечисляй их в ответе.""";

    private static final int PREVIEW_CHARS = 400;

    private final RagRetrievalService retrievalService;
    private final OpenRouterHttpClient openRouterHttpClient;
    private final RagCitationBuilder citationBuilder;
    private final RagRelevanceGuard relevanceGuard;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final double defaultMinSimilarity;
    private final int searchPoolSize;

    public RagQueryService(
            RagRetrievalService retrievalService,
            OpenRouterHttpClient openRouterHttpClient,
            RagCitationBuilder citationBuilder,
            RagRelevanceGuard relevanceGuard,
            @Value("${app.openrouter.model}") String model,
            @Value("${app.agent.temperature}") double temperature,
            @Value("${app.agent.max-tokens}") int maxTokens,
            @Value("${app.rag.min-similarity:0.65}") double defaultMinSimilarity,
            @Value("${app.rag.search-pool-size:20}") int searchPoolSize) {
        this.retrievalService = retrievalService;
        this.openRouterHttpClient = openRouterHttpClient;
        this.citationBuilder = citationBuilder;
        this.relevanceGuard = relevanceGuard;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.defaultMinSimilarity = defaultMinSimilarity;
        this.searchPoolSize = searchPoolSize;
    }

    public RagQueryResponse query(
            String question,
            boolean useRag,
            ChunkingStrategy strategy,
            Integer topK,
            RagRetrievalMode mode,
            Double minSimilarity) {
        if (!useRag) {
            String answer = completeWithoutRag(question);
            return new RagQueryResponse(answer, List.of(), "WITHOUT_RAG");
        }

        RagRetrievalMode effectiveMode = mode != null ? mode : RagRetrievalMode.FILTERED;
        RagRetrievalService.RetrievalResult retrieval = retrievalService.retrieve(
                question, strategy, effectiveMode, topK, minSimilarity);
        return buildRagResponse(question, retrieval);
    }

    public RagQueryResponse query(String question, boolean useRag, ChunkingStrategy strategy, Integer topK) {
        return query(question, useRag, strategy, topK, RagRetrievalMode.RAW, null);
    }

    public RagQueryResponse queryChatTurn(
            String message,
            List<RagChatMessageDto> history,
            RagDialogMemoryDto taskMemory,
            ChunkingStrategy strategy,
            Integer topK,
            RagRetrievalMode mode,
            Double minSimilarity) {
        RagRetrievalMode effectiveMode = mode != null ? mode : RagRetrievalMode.REWRITE_FILTERED;
        String searchQuery = buildEnrichedSearchQuery(message, taskMemory);
        RagRetrievalService.RetrievalResult retrieval = retrievalService.retrieve(
                searchQuery, strategy, effectiveMode, topK, minSimilarity);
        return buildChatRagResponse(message, history, taskMemory, retrieval);
    }

    public RagQueryCompareResponse compare(String question, ChunkingStrategy strategy, Integer topK) {
        RagQueryResponse withoutRag = query(question, false, strategy, topK);
        RagQueryResponse withRag = query(question, true, strategy, topK, RagRetrievalMode.FILTERED, null);
        return new RagQueryCompareResponse(question, withoutRag, withRag);
    }

    public RagModeCompareResponse compareModes(
            String question,
            ChunkingStrategy strategy,
            Integer topK,
            Double minSimilarity) {
        double threshold = minSimilarity != null ? minSimilarity : defaultMinSimilarity;
        RagModeResultDto raw = runMode(question, strategy, topK, threshold, RagRetrievalMode.RAW);
        RagModeResultDto filtered = runMode(question, strategy, topK, threshold, RagRetrievalMode.FILTERED);
        RagModeResultDto rewriteFiltered = runMode(question, strategy, topK, threshold, RagRetrievalMode.REWRITE_FILTERED);

        return new RagModeCompareResponse(
                question,
                rewriteFiltered.retrieval().rewrittenQuery(),
                threshold,
                searchPoolSize,
                raw,
                filtered,
                rewriteFiltered);
    }

    private RagModeResultDto runMode(
            String question,
            ChunkingStrategy strategy,
            Integer topK,
            double threshold,
            RagRetrievalMode mode) {
        RagRetrievalService.RetrievalResult retrieval = retrievalService.retrieve(
                question, strategy, mode, topK, threshold);
        RagQueryResponse response = buildRagResponse(question, retrieval);
        return new RagModeResultDto(mode, response, toMeta(retrieval));
    }

    private RagQueryResponse buildRagResponse(String question, RagRetrievalService.RetrievalResult retrieval) {
        RagRetrievalMetaDto meta = toMeta(retrieval);
        RagConfidence confidence = relevanceGuard.assess(retrieval);

        if (relevanceGuard.shouldRefuse(confidence)) {
            return new RagQueryResponse(
                    RagRelevanceGuard.UNKNOWN_ANSWER.trim(),
                    List.of(),
                    retrieval.mode().name(),
                    meta,
                    List.of(),
                    List.of(),
                    RagConfidence.UNKNOWN.name());
        }

        List<RagRetrievalService.ScoredChunk> chunks = retrieval.chunks();
        RagCitationBuilder.CitationBundle citations = citationBuilder.build(
                retrieval.originalQuery(), retrieval.searchQuery(), chunks);
        java.util.Map<String, String> quoteByChunkId = citations.quotes().stream()
                .collect(java.util.stream.Collectors.toMap(
                        q -> q.chunkId(),
                        q -> q.text(),
                        (left, right) -> left,
                        java.util.LinkedHashMap::new));
        List<RagQueryResponse.ChunkUsedDto> used = chunks.stream()
                .map(chunk -> toChunkUsed(chunk, quoteByChunkId.get(chunk.chunkId())))
                .toList();

        String contextBlock = buildContextBlock(chunks);
        String userMessage = contextBlock + "\n\nВопрос: " + question;

        CompletionResult completion = openRouterHttpClient.complete(
                model,
                temperature,
                maxTokens,
                List.of(
                        new OpenRouterHttpClient.ChatMessage("system", RAG_SYSTEM_PROMPT),
                        new OpenRouterHttpClient.ChatMessage("user", userMessage)),
                false);

        return new RagQueryResponse(
                completion.content(),
                used,
                retrieval.mode().name(),
                meta,
                citations.sources(),
                citations.quotes(),
                confidence.name());
    }

    private RagQueryResponse.ChunkUsedDto toChunkUsed(RagRetrievalService.ScoredChunk chunk, String quoteExcerpt) {
        String preview;
        if (quoteExcerpt != null && !quoteExcerpt.isBlank()) {
            preview = quoteExcerpt;
        } else {
            preview = chunk.content().substring(0, Math.min(PREVIEW_CHARS, chunk.content().length()));
        }
        return new RagQueryResponse.ChunkUsedDto(
                chunk.chunkId(),
                chunk.source(),
                chunk.section(),
                chunk.semanticScore(),
                preview);
    }

    private RagRetrievalMetaDto toMeta(RagRetrievalService.RetrievalResult retrieval) {
        return new RagRetrievalMetaDto(
                retrieval.mode(),
                retrieval.originalQuery(),
                retrieval.rewrittenQuery(),
                retrieval.searchQuery(),
                retrieval.topKBefore(),
                retrieval.topKAfter(),
                retrieval.droppedCount(),
                retrieval.minSimilarity(),
                retrieval.scoresBefore(),
                retrieval.scoresAfter());
    }

    private String completeWithoutRag(String question) {
        CompletionResult result = openRouterHttpClient.complete(
                model,
                temperature,
                maxTokens,
                List.of(
                        new OpenRouterHttpClient.ChatMessage(
                                "system",
                                "Ты учебный ассистент. Ответь кратко без доступа к документу."),
                        new OpenRouterHttpClient.ChatMessage("user", question)),
                false);
        return result.content();
    }

    private RagQueryResponse buildChatRagResponse(
            String message,
            List<RagChatMessageDto> history,
            RagDialogMemoryDto taskMemory,
            RagRetrievalService.RetrievalResult retrieval) {
        RagRetrievalMetaDto meta = toMeta(retrieval);
        RagConfidence confidence = relevanceGuard.assess(retrieval);

        if (relevanceGuard.shouldRefuse(confidence)) {
            return new RagQueryResponse(
                    RagRelevanceGuard.UNKNOWN_ANSWER.trim(),
                    List.of(),
                    retrieval.mode().name(),
                    meta,
                    List.of(),
                    List.of(),
                    RagConfidence.UNKNOWN.name());
        }

        List<RagRetrievalService.ScoredChunk> chunks = retrieval.chunks();
        RagCitationBuilder.CitationBundle citations = citationBuilder.build(
                retrieval.originalQuery(), retrieval.searchQuery(), chunks);
        java.util.Map<String, String> quoteByChunkId = citations.quotes().stream()
                .collect(java.util.stream.Collectors.toMap(
                        q -> q.chunkId(),
                        q -> q.text(),
                        (left, right) -> left,
                        java.util.LinkedHashMap::new));
        List<RagQueryResponse.ChunkUsedDto> used = chunks.stream()
                .map(chunk -> toChunkUsed(chunk, quoteByChunkId.get(chunk.chunkId())))
                .toList();

        String contextBlock = buildContextBlock(chunks);
        List<OpenRouterHttpClient.ChatMessage> chatMessages = buildChatMessages(
                message, history, taskMemory, contextBlock);

        CompletionResult completion = openRouterHttpClient.complete(
                model, temperature, maxTokens, chatMessages, false);

        return new RagQueryResponse(
                completion.content(),
                used,
                retrieval.mode().name(),
                meta,
                citations.sources(),
                citations.quotes(),
                confidence.name());
    }

    private List<OpenRouterHttpClient.ChatMessage> buildChatMessages(
            String message,
            List<RagChatMessageDto> history,
            RagDialogMemoryDto taskMemory,
            String contextBlock) {
        List<OpenRouterHttpClient.ChatMessage> messages = new ArrayList<>();
        messages.add(new OpenRouterHttpClient.ChatMessage("system", RAG_CHAT_SYSTEM_PROMPT));

        if (taskMemory != null && hasTaskMemory(taskMemory)) {
            StringBuilder memoryBlock = new StringBuilder("Память задачи диалога:\n");
            if (taskMemory.dialogGoal() != null && !taskMemory.dialogGoal().isBlank()) {
                memoryBlock.append("- Цель: ").append(taskMemory.dialogGoal()).append("\n");
            }
            if (!taskMemory.clarifications().isEmpty()) {
                memoryBlock.append("- Уточнения: ").append(String.join("; ", taskMemory.clarifications())).append("\n");
            }
            if (!taskMemory.fixedTerms().isEmpty()) {
                memoryBlock.append("- Зафиксированные термины: ")
                        .append(String.join("; ", taskMemory.fixedTerms())).append("\n");
            }
            messages.add(new OpenRouterHttpClient.ChatMessage("system", memoryBlock.toString().trim()));
        }

        if (history != null) {
            for (RagChatMessageDto turn : history) {
                if ("user".equals(turn.role()) || "assistant".equals(turn.role())) {
                    messages.add(new OpenRouterHttpClient.ChatMessage(turn.role(), turn.content()));
                }
            }
        }

        messages.add(new OpenRouterHttpClient.ChatMessage(
                "user",
                contextBlock + "\n\nТекущий вопрос: " + message));
        return messages;
    }

    private static boolean hasTaskMemory(RagDialogMemoryDto taskMemory) {
        return (taskMemory.dialogGoal() != null && !taskMemory.dialogGoal().isBlank())
                || !taskMemory.clarifications().isEmpty()
                || !taskMemory.fixedTerms().isEmpty();
    }

    private static String buildEnrichedSearchQuery(String message, RagDialogMemoryDto taskMemory) {
        if (taskMemory == null || !hasTaskMemory(taskMemory)) {
            return message;
        }
        StringBuilder builder = new StringBuilder(message);
        if (taskMemory.dialogGoal() != null && !taskMemory.dialogGoal().isBlank()) {
            builder.append(" | цель: ").append(taskMemory.dialogGoal());
        }
        for (String clarification : taskMemory.clarifications()) {
            builder.append(" | ").append(clarification);
        }
        for (String term : taskMemory.fixedTerms()) {
            builder.append(" | термин: ").append(term);
        }
        return builder.toString();
    }

    private String buildContextBlock(List<RagRetrievalService.ScoredChunk> chunks) {
        StringBuilder builder = new StringBuilder("Контекст из «Основы православия»:\n\n");
        for (int i = 0; i < chunks.size(); i++) {
            RagRetrievalService.ScoredChunk chunk = chunks.get(i);
            builder.append("--- Фрагмент ").append(i + 1)
                    .append(" [id=").append(chunk.chunkId())
                    .append(", ").append(chunk.section())
                    .append(", score=").append(String.format("%.3f", chunk.semanticScore())).append("] ---\n");
            builder.append(chunk.content()).append("\n\n");
        }
        return builder.toString();
    }
}
