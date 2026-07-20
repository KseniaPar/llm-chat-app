package com.example.llmchat.rag;

import com.example.llmchat.rag.chunk.StructureAwareChunker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class SupportFaqIndexingService {

    private static final Logger log = LoggerFactory.getLogger(SupportFaqIndexingService.class);

    private final SupportFaqCorpusLoader corpusLoader;
    private final StructureAwareChunker structureAwareChunker;
    private final EmbeddingService embeddingService;
    private final RagIndexStore indexStore;
    private final boolean autoIndexOnStartup;

    public SupportFaqIndexingService(
            SupportFaqCorpusLoader corpusLoader,
            StructureAwareChunker structureAwareChunker,
            EmbeddingService embeddingService,
            RagIndexStore indexStore,
            @Value("${app.rag.support-auto-index-on-startup:true}") boolean autoIndexOnStartup) {
        this.corpusLoader = corpusLoader;
        this.structureAwareChunker = structureAwareChunker;
        this.embeddingService = embeddingService;
        this.indexStore = indexStore;
        this.autoIndexOnStartup = autoIndexOnStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureSupportIndexOnStartup() {
        if (!autoIndexOnStartup) {
            return;
        }
        int chunks = indexStore.support().countChunks(ChunkingStrategy.STRUCTURE);
        if (chunks > 0) {
            log.info("Support FAQ RAG index ready: {} STRUCTURE chunks", chunks);
            return;
        }
        try {
            log.info("Support FAQ RAG index empty — indexing support/faq…");
            RagIndexingService.IndexResult result = buildIndex();
            log.info("Support FAQ RAG index built: {} chunks", result.chunkCount());
        } catch (Exception exception) {
            log.warn("Auto support FAQ index skipped: {}", exception.getMessage());
        }
    }

    public RagIndexingService.IndexResult buildIndex() {
        RagIndexRepository indexRepository = indexStore.support();
        List<RagDocument> documents = corpusLoader.loadAll();
        if (documents.isEmpty()) {
            throw new IllegalStateException("Support FAQ corpus is empty — add files under support/faq/");
        }
        ChunkingStrategy strategy = ChunkingStrategy.STRUCTURE;
        indexRepository.clearStrategy(strategy);
        List<RagChunk> allChunks = new ArrayList<>();
        for (RagDocument document : documents) {
            long docId = indexRepository.upsertDocument(document);
            List<RagChunk> chunks = structureAwareChunker.chunk(document);
            if (chunks.isEmpty()) {
                continue;
            }
            List<String> texts = chunks.stream().map(RagChunk::content).toList();
            List<float[]> embeddings = embeddingService.embedBatch(texts, RagStack.SUPPORT);
            for (int i = 0; i < chunks.size(); i++) {
                indexRepository.insertChunk(docId, strategy, chunks.get(i), embeddings.get(i));
            }
            allChunks.addAll(chunks);
        }
        RagIndexingService.IndexResult result = buildStats(allChunks);
        indexRepository.recordIndexRun(
                strategy,
                result.chunkCount(),
                result.avgChunkSize(),
                result.minChunkSize(),
                result.maxChunkSize());
        return result;
    }

    public SupportIndexStatus status() {
        int chunkCount = indexStore.support().countChunks(ChunkingStrategy.STRUCTURE);
        SupportFaqCorpusLoader.CorpusStats corpus = corpusLoader.stats();
        return new SupportIndexStatus(
                indexStore.supportPath(),
                corpus.documentCount(),
                corpus.totalChars(),
                chunkCount,
                chunkCount > 0,
                corpusLoader.repoRoot().toString());
    }

    private RagIndexingService.IndexResult buildStats(List<RagChunk> chunks) {
        if (chunks.isEmpty()) {
            return new RagIndexingService.IndexResult(ChunkingStrategy.STRUCTURE, 0, 0, 0, 0, List.of());
        }
        int min = chunks.stream().mapToInt(c -> c.content().length()).min().orElse(0);
        int max = chunks.stream().mapToInt(c -> c.content().length()).max().orElse(0);
        double avg = chunks.stream().mapToInt(c -> c.content().length()).average().orElse(0);
        List<RagIndexingService.ChunkSample> samples = chunks.stream()
                .limit(3)
                .map(c -> new RagIndexingService.ChunkSample(
                        c.chunkId(),
                        c.section(),
                        c.content().substring(0, Math.min(120, c.content().length()))))
                .toList();
        return new RagIndexingService.IndexResult(
                ChunkingStrategy.STRUCTURE, chunks.size(), avg, min, max, samples);
    }

    public record SupportIndexStatus(
            String indexPath,
            int documentCount,
            int totalChars,
            int chunkCount,
            boolean ready,
            String repoRoot) {
    }
}
