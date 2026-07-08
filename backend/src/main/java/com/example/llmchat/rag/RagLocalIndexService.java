package com.example.llmchat.rag;

import com.example.llmchat.localllm.OllamaHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
public class RagLocalIndexService {

    private static final Logger log = LoggerFactory.getLogger(RagLocalIndexService.class);

    private final RagIndexingService indexingService;
    private final RagIndexStore indexStore;
    private final EmbeddingService embeddingService;
    private final OllamaHttpClient ollamaHttpClient;
    private final String chatModel;
    private final boolean autoIndexOnStartup;
    private final boolean warmupOnReady;

    public RagLocalIndexService(
            RagIndexingService indexingService,
            RagIndexStore indexStore,
            EmbeddingService embeddingService,
            OllamaHttpClient ollamaHttpClient,
            @Value("${app.local-llm.model}") String chatModel,
            @Value("${app.rag.local.auto-index-on-startup:true}") boolean autoIndexOnStartup,
            @Value("${app.rag.local.warmup-on-ready:true}") boolean warmupOnReady) {
        this.indexingService = indexingService;
        this.indexStore = indexStore;
        this.embeddingService = embeddingService;
        this.ollamaHttpClient = ollamaHttpClient;
        this.chatModel = chatModel;
        this.autoIndexOnStartup = autoIndexOnStartup;
        this.warmupOnReady = warmupOnReady;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureLocalIndexOnStartup() {
        if (!autoIndexOnStartup) {
            return;
        }
        int chunks = indexStore.local().countChunks(ChunkingStrategy.STRUCTURE);
        if (chunks > 0) {
            log.info("Local RAG index ready: {} STRUCTURE chunks", chunks);
            warmupLocalChatModelAsync();
            return;
        }
        try {
            log.info("Local RAG index empty — building with Ollama {}…", embeddingService.localModel());
            RagIndexingService.IndexResult result = buildLocalIndex();
            log.info("Local RAG index built: {} chunks", result.chunkCount());
            warmupLocalChatModelAsync();
        } catch (Exception exception) {
            log.warn("Auto local index skipped: {}", exception.getMessage());
        }
    }

    private void warmupLocalChatModelAsync() {
        if (!warmupOnReady) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                log.info("Warming up Ollama chat model {}…", chatModel);
                ollamaHttpClient.chat("Ответь одним словом: да", chatModel, 0.0, 8);
                log.info("Ollama chat model {} ready", chatModel);
            } catch (Exception exception) {
                log.warn("Ollama chat warmup skipped: {}", exception.getMessage());
            }
        });
    }

    public RagIndexingService.IndexResult buildLocalIndex() {
        return buildLocalIndex(ChunkingStrategy.STRUCTURE);
    }

    public RagIndexingService.IndexResult buildLocalIndex(ChunkingStrategy strategy) {
        return indexingService.index(strategy, RagStack.LOCAL);
    }

    public LocalIndexStatus status() {
        int localChunks = indexStore.local().countChunks(ChunkingStrategy.STRUCTURE);
        int cloudChunks = indexStore.cloud().countChunks(ChunkingStrategy.STRUCTURE);
        return new LocalIndexStatus(
                indexStore.localPath(),
                indexStore.cloudPath(),
                embeddingService.localModel(),
                embeddingService.cloudModel(),
                localChunks,
                cloudChunks,
                localChunks > 0);
    }

    public record LocalIndexStatus(
            String localIndexPath,
            String cloudIndexPath,
            String localEmbeddingModel,
            String cloudEmbeddingModel,
            int localChunkCount,
            int cloudChunkCount,
            boolean localIndexReady) {
    }
}
