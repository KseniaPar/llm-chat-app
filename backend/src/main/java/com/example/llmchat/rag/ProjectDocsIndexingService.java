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
public class ProjectDocsIndexingService {

    private static final Logger log = LoggerFactory.getLogger(ProjectDocsIndexingService.class);

    private final ProjectDocsCorpusLoader corpusLoader;
    private final StructureAwareChunker structureAwareChunker;
    private final EmbeddingService embeddingService;
    private final RagIndexStore indexStore;
    private final boolean autoIndexOnStartup;

    public ProjectDocsIndexingService(
            ProjectDocsCorpusLoader corpusLoader,
            StructureAwareChunker structureAwareChunker,
            EmbeddingService embeddingService,
            RagIndexStore indexStore,
            @Value("${app.rag.project-auto-index-on-startup:true}") boolean autoIndexOnStartup) {
        this.corpusLoader = corpusLoader;
        this.structureAwareChunker = structureAwareChunker;
        this.embeddingService = embeddingService;
        this.indexStore = indexStore;
        this.autoIndexOnStartup = autoIndexOnStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureProjectIndexOnStartup() {
        if (!autoIndexOnStartup) {
            return;
        }
        int chunks = indexStore.project().countChunks(ChunkingStrategy.STRUCTURE);
        if (chunks > 0) {
            log.info("Project RAG index ready: {} STRUCTURE chunks", chunks);
            return;
        }
        try {
            log.info("Project RAG index empty — indexing README + project/docs…");
            RagIndexingService.IndexResult result = buildIndex();
            log.info("Project RAG index built: {} chunks", result.chunkCount());
        } catch (Exception exception) {
            log.warn("Auto project index skipped: {}", exception.getMessage());
        }
    }

    public RagIndexingService.IndexResult buildIndex() {
        RagIndexRepository indexRepository = indexStore.project();
        List<RagDocument> documents = corpusLoader.loadAll();
        if (documents.isEmpty()) {
            throw new IllegalStateException(
                    "Project docs corpus is empty — add README.md and/or files under project/docs/");
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
            List<float[]> embeddings = embeddingService.embedBatch(texts, RagStack.PROJECT);
            if (embeddings.size() != chunks.size()) {
                throw new IllegalStateException(
                        "Embeddings count mismatch for " + document.title()
                                + ": chunks=" + chunks.size() + ", embeddings=" + embeddings.size());
            }
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

    public ProjectIndexStatus status() {
        int chunkCount = indexStore.project().countChunks(ChunkingStrategy.STRUCTURE);
        ProjectDocsCorpusLoader.CorpusStats corpus = corpusLoader.stats();
        return new ProjectIndexStatus(
                indexStore.projectPath(),
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

    public record ProjectIndexStatus(
            String indexPath,
            int documentCount,
            int totalChars,
            int chunkCount,
            boolean ready,
            String repoRoot) {
    }
}
