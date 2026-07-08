package com.example.llmchat.rag;

import com.example.llmchat.agent.OpenRouterHttpClient;
import com.example.llmchat.dto.RagChatMessageDto;
import com.example.llmchat.dto.RagDialogMemoryDto;
import com.example.llmchat.dto.RagLlmCompareResponse;
import com.example.llmchat.dto.RagModeCompareResponse;
import com.example.llmchat.dto.RagModeResultDto;
import com.example.llmchat.dto.RagQueryCompareResponse;
import com.example.llmchat.dto.RagQueryResponse;
import com.example.llmchat.dto.RagRetrievalMetaDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    private final RagCompletionService completionService;
    private final RagCitationBuilder citationBuilder;
    private final RagRelevanceGuard relevanceGuard;
    private final double defaultMinSimilarity;
    private final int searchPoolSize;

    public RagQueryService(
            RagRetrievalService retrievalService,
            RagCompletionService completionService,
            RagCitationBuilder citationBuilder,
            RagRelevanceGuard relevanceGuard,
            @Value("${app.rag.min-similarity:0.65}") double defaultMinSimilarity,
            @Value("${app.rag.search-pool-size:20}") int searchPoolSize) {
        this.retrievalService = retrievalService;
        this.completionService = completionService;
        this.citationBuilder = citationBuilder;
        this.relevanceGuard = relevanceGuard;
        this.defaultMinSimilarity = defaultMinSimilarity;
        this.searchPoolSize = searchPoolSize;
    }

    public String localModelName() {
        return completionService.localModel();
    }

    public String cloudModelName() {
        return completionService.cloudModel();
    }

    public RagQueryResponse query(
            String question,
            boolean useRag,
            ChunkingStrategy strategy,
            Integer topK,
            RagRetrievalMode mode,
            Double minSimilarity,
            RagLlmProvider llmProvider) {
        RagLlmProvider provider = llmProvider != null ? llmProvider : RagLlmProvider.LOCAL;
        if (!useRag) {
            return completeWithoutRag(question, provider);
        }

        RagStack stack = stackFor(provider);
        RagRetrievalMode effectiveMode = resolveRetrievalMode(mode, provider);
        long retrievalStarted = System.currentTimeMillis();
        RagRetrievalService.RetrievalResult retrieval = retrievalService.retrieve(
                question, strategy, effectiveMode, topK, minSimilarity, stack);
        long retrievalDurationMs = System.currentTimeMillis() - retrievalStarted;
        return buildRagResponse(question, retrieval, provider, retrievalDurationMs);
    }

    public RagQueryResponse query(
            String question,
            boolean useRag,
            ChunkingStrategy strategy,
            Integer topK,
            RagRetrievalMode mode,
            Double minSimilarity) {
        return query(question, useRag, strategy, topK, mode, minSimilarity, RagLlmProvider.LOCAL);
    }

    public RagQueryResponse query(String question, boolean useRag, ChunkingStrategy strategy, Integer topK) {
        return query(question, useRag, strategy, topK, RagRetrievalMode.RAW, null, RagLlmProvider.LOCAL);
    }

    public RagQueryResponse queryChatTurn(
            String message,
            List<RagChatMessageDto> history,
            RagDialogMemoryDto taskMemory,
            ChunkingStrategy strategy,
            Integer topK,
            RagRetrievalMode mode,
            Double minSimilarity,
            RagLlmProvider llmProvider) {
        RagLlmProvider provider = llmProvider != null ? llmProvider : RagLlmProvider.LOCAL;
        RagStack stack = stackFor(provider);
        RagRetrievalMode effectiveMode = resolveRetrievalMode(mode, provider);
        String searchQuery = buildEnrichedSearchQuery(message, taskMemory);
        long retrievalStarted = System.currentTimeMillis();
        RagRetrievalService.RetrievalResult retrieval = retrievalService.retrieve(
                searchQuery, strategy, effectiveMode, topK, minSimilarity, stack);
        long retrievalDurationMs = System.currentTimeMillis() - retrievalStarted;
        return buildChatRagResponse(message, history, taskMemory, retrieval, provider, retrievalDurationMs);
    }

    public RagQueryResponse queryChatTurn(
            String message,
            List<RagChatMessageDto> history,
            RagDialogMemoryDto taskMemory,
            ChunkingStrategy strategy,
            Integer topK,
            RagRetrievalMode mode,
            Double minSimilarity) {
        return queryChatTurn(message, history, taskMemory, strategy, topK, mode, minSimilarity, RagLlmProvider.LOCAL);
    }

    public RagLlmCompareResponse compareLlmProviders(
            String question,
            ChunkingStrategy strategy,
            Integer topK,
            Double minSimilarity) {
        RagRetrievalMode mode = RagRetrievalMode.FILTERED;

        long localRetrievalStarted = System.currentTimeMillis();
        RagRetrievalService.RetrievalResult localRetrieval = retrievalService.retrieve(
                question, strategy, mode, topK, minSimilarity, RagStack.LOCAL);
        long localRetrievalMs = System.currentTimeMillis() - localRetrievalStarted;

        long cloudRetrievalStarted = System.currentTimeMillis();
        RagRetrievalService.RetrievalResult cloudRetrieval = retrievalService.retrieve(
                question, strategy, mode, topK, minSimilarity, RagStack.CLOUD);
        long cloudRetrievalMs = System.currentTimeMillis() - cloudRetrievalStarted;

        RagQueryResponse local = buildRagResponse(question, localRetrieval, RagLlmProvider.LOCAL, localRetrievalMs);
        RagQueryResponse cloud = buildRagResponse(question, cloudRetrieval, RagLlmProvider.CLOUD, cloudRetrievalMs);

        long localMs = local.generationDurationMs() != null ? local.generationDurationMs() : 0;
        long cloudMs = cloud.generationDurationMs() != null ? cloud.generationDurationMs() : 0;
        String speedWinner;
        if (!Boolean.TRUE.equals(local.generationSuccess()) && !Boolean.TRUE.equals(cloud.generationSuccess())) {
            speedWinner = "—";
        } else if (!Boolean.TRUE.equals(local.generationSuccess())) {
            speedWinner = "CLOUD";
        } else if (!Boolean.TRUE.equals(cloud.generationSuccess())) {
            speedWinner = "LOCAL";
        } else if (localMs < cloudMs) {
            speedWinner = "LOCAL";
        } else if (cloudMs < localMs) {
            speedWinner = "CLOUD";
        } else {
            speedWinner = "TIE";
        }

        int localSources = local.sources() != null ? local.sources().size() : 0;
        int cloudSources = cloud.sources() != null ? cloud.sources().size() : 0;
        String qualityNote = buildQualityNote(local, cloud, localSources, cloudSources)
                + " Retrieval: LOCAL (Ollama index) vs CLOUD (OpenRouter index).";

        return new RagLlmCompareResponse(
                question,
                local,
                cloud,
                new RagLlmCompareResponse.RagLlmCompareSummaryDto(
                        localMs,
                        cloudMs,
                        localRetrievalMs + cloudRetrievalMs,
                        speedWinner,
                        localSources,
                        cloudSources,
                        Boolean.TRUE.equals(local.generationSuccess()),
                        Boolean.TRUE.equals(cloud.generationSuccess()),
                        qualityNote,
                        buildStabilityNote(local, cloud)));
    }

    public RagQueryCompareResponse compare(String question, ChunkingStrategy strategy, Integer topK) {
        RagQueryResponse withoutRag = query(question, false, strategy, topK, null, null, RagLlmProvider.LOCAL);
        RagQueryResponse withRag = query(
                question, true, strategy, topK, RagRetrievalMode.FILTERED, null, RagLlmProvider.LOCAL);
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
        long retrievalStarted = System.currentTimeMillis();
        RagRetrievalService.RetrievalResult retrieval = retrievalService.retrieve(
                question, strategy, mode, topK, threshold, RagStack.LOCAL);
        long retrievalDurationMs = System.currentTimeMillis() - retrievalStarted;
        RagQueryResponse response = buildRagResponse(
                question, retrieval, RagLlmProvider.LOCAL, retrievalDurationMs);
        return new RagModeResultDto(mode, response, toMeta(retrieval));
    }

    private RagQueryResponse buildRagResponse(
            String question,
            RagRetrievalService.RetrievalResult retrieval,
            RagLlmProvider provider,
            long retrievalDurationMs) {
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
                    RagConfidence.UNKNOWN.name(),
                    provider,
                    modelFor(provider),
                    retrievalDurationMs,
                    0L,
                    0L,
                    true,
                    null);
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

        RagLlmCompletionResult completion = completionService.complete(
                List.of(
                        new OpenRouterHttpClient.ChatMessage("system", RAG_SYSTEM_PROMPT),
                        new OpenRouterHttpClient.ChatMessage("user", userMessage)),
                provider);

        String answer = completion.success()
                ? completion.content()
                : "Ошибка генерации (" + provider.name() + "): " + completion.errorMessage();

        return new RagQueryResponse(
                answer,
                used,
                retrieval.mode().name(),
                meta,
                citations.sources(),
                citations.quotes(),
                confidence.name(),
                provider,
                completion.model(),
                retrievalDurationMs,
                completion.durationMs(),
                completion.tokenCount(),
                completion.success(),
                completion.errorMessage());
    }

    private RagQueryResponse buildChatRagResponse(
            String message,
            List<RagChatMessageDto> history,
            RagDialogMemoryDto taskMemory,
            RagRetrievalService.RetrievalResult retrieval,
            RagLlmProvider provider,
            long retrievalDurationMs) {
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
                    RagConfidence.UNKNOWN.name(),
                    provider,
                    modelFor(provider),
                    retrievalDurationMs,
                    0L,
                    0L,
                    true,
                    null);
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

        RagLlmCompletionResult completion = completionService.complete(chatMessages, provider);

        String answer = completion.success()
                ? completion.content()
                : "Ошибка генерации (" + provider.name() + "): " + completion.errorMessage();

        return new RagQueryResponse(
                answer,
                used,
                retrieval.mode().name(),
                meta,
                citations.sources(),
                citations.quotes(),
                confidence.name(),
                provider,
                completion.model(),
                retrievalDurationMs,
                completion.durationMs(),
                completion.tokenCount(),
                completion.success(),
                completion.errorMessage());
    }

    private RagQueryResponse completeWithoutRag(String question, RagLlmProvider provider) {
        RagLlmCompletionResult completion = completionService.complete(
                List.of(
                        new OpenRouterHttpClient.ChatMessage(
                                "system",
                                "Ты учебный ассистент. Ответь кратко без доступа к документу."),
                        new OpenRouterHttpClient.ChatMessage("user", question)),
                provider);

        String answer = completion.success()
                ? completion.content()
                : "Ошибка генерации (" + provider.name() + "): " + completion.errorMessage();

        return new RagQueryResponse(
                answer,
                List.of(),
                "WITHOUT_RAG",
                null,
                List.of(),
                List.of(),
                null,
                provider,
                completion.model(),
                null,
                completion.durationMs(),
                completion.tokenCount(),
                completion.success(),
                completion.errorMessage());
    }

    private static RagStack stackFor(RagLlmProvider provider) {
        return provider == RagLlmProvider.LOCAL ? RagStack.LOCAL : RagStack.CLOUD;
    }

    private RagRetrievalMode resolveRetrievalMode(RagRetrievalMode mode, RagLlmProvider provider) {
        RagRetrievalMode requested = mode != null ? mode : RagRetrievalMode.FILTERED;
        if (provider == RagLlmProvider.LOCAL && requested == RagRetrievalMode.REWRITE_FILTERED) {
            return RagRetrievalMode.FILTERED;
        }
        return requested;
    }

    private String modelFor(RagLlmProvider provider) {
        return provider == RagLlmProvider.LOCAL
                ? completionService.localModel()
                : completionService.cloudModel();
    }

    private static String buildQualityNote(
            RagQueryResponse local,
            RagQueryResponse cloud,
            int localSources,
            int cloudSources) {
        if (!Boolean.TRUE.equals(local.generationSuccess()) && !Boolean.TRUE.equals(cloud.generationSuccess())) {
            return "Обе модели вернули ошибку.";
        }
        if (localSources > cloudSources) {
            return "Локальная модель: больше источников (" + localSources + " vs " + cloudSources + ").";
        }
        if (cloudSources > localSources) {
            return "Облачная модель: больше источников (" + cloudSources + " vs " + localSources + ").";
        }
        String localConf = local.confidence() != null ? local.confidence() : "—";
        String cloudConf = cloud.confidence() != null ? cloud.confidence() : "—";
        return "Одинаковое число источников; confidence LOCAL=" + localConf + ", CLOUD=" + cloudConf + ".";
    }

    private static String buildStabilityNote(RagQueryResponse local, RagQueryResponse cloud) {
        boolean localOk = Boolean.TRUE.equals(local.generationSuccess());
        boolean cloudOk = Boolean.TRUE.equals(cloud.generationSuccess());
        if (localOk && cloudOk) {
            return "Обе модели ответили успешно на одном контексте.";
        }
        if (!localOk && !cloudOk) {
            return "Обе модели завершились с ошибкой.";
        }
        return localOk
                ? "Локальная модель стабильна; облачная вернула ошибку."
                : "Облачная модель стабильна; локальная вернула ошибку: "
                        + (local.generationError() != null ? local.generationError() : "unknown");
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
                retrieval.scoresAfter(),
                retrieval.embeddingSource());
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
                    .append(", score=").append(String.format(Locale.ROOT, "%.3f", chunk.semanticScore())).append("] ---\n");
            builder.append(chunk.content()).append("\n\n");
        }
        return builder.toString();
    }
}
