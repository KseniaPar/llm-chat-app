package com.example.llmchat.rag;

import com.example.llmchat.rag.chunk.FixedSizeChunker;
import com.example.llmchat.rag.chunk.StructureAwareChunker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RagIndexingService {

    private final RagCorpusLoader corpusLoader;
    private final FixedSizeChunker fixedSizeChunker;
    private final StructureAwareChunker structureAwareChunker;
    private final EmbeddingService embeddingService;
    private final RagIndexStore indexStore;
    private final int fixedChunkSize;
    private final int chunkOverlap;

    public RagIndexingService(
            RagCorpusLoader corpusLoader,
            FixedSizeChunker fixedSizeChunker,
            StructureAwareChunker structureAwareChunker,
            EmbeddingService embeddingService,
            RagIndexStore indexStore,
            @Value("${app.rag.fixed-chunk-size:1200}") int fixedChunkSize,
            @Value("${app.rag.chunk-overlap:200}") int chunkOverlap) {
        this.corpusLoader = corpusLoader;
        this.fixedSizeChunker = fixedSizeChunker;
        this.structureAwareChunker = structureAwareChunker;
        this.embeddingService = embeddingService;
        this.indexStore = indexStore;
        this.fixedChunkSize = fixedChunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    public IndexResult index(ChunkingStrategy strategy) {
        return index(strategy, RagStack.CLOUD);
    }

    public IndexResult index(ChunkingStrategy strategy, RagStack stack) {
        RagIndexRepository indexRepository = indexStore.forStack(stack);
        List<RagDocument> documents = corpusLoader.loadAll();
        if (documents.isEmpty()) {
            throw new IllegalStateException("Corpus is empty — add files to rag-corpus/");
        }
        indexRepository.clearStrategy(strategy);
        List<RagChunk> allChunks = new ArrayList<>();
        for (RagDocument document : documents) {
            long docId = indexRepository.upsertDocument(document);
            List<RagChunk> chunks = chunk(document, strategy);
            if (chunks.isEmpty()) {
                continue;
            }
            List<String> texts = chunks.stream().map(RagChunk::content).toList();
            List<float[]> embeddings = embeddingService.embedBatch(texts, stack);
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
        IndexResult result = buildStats(strategy, allChunks);
        indexRepository.recordIndexRun(
                strategy,
                result.chunkCount(),
                result.avgChunkSize(),
                result.minChunkSize(),
                result.maxChunkSize());
        return result;
    }

    public CompareResult compareStrategies() {
        IndexResult fixed = index(ChunkingStrategy.FIXED_SIZE);
        IndexResult structure = index(ChunkingStrategy.STRUCTURE);
        return new CompareResult(fixed, structure);
    }

    private List<RagChunk> chunk(RagDocument document, ChunkingStrategy strategy) {
        return switch (strategy) {
            case FIXED_SIZE -> fixedSizeChunker.chunk(document, fixedChunkSize, chunkOverlap);
            case STRUCTURE -> structureAwareChunker.chunk(document);
        };
    }

    private IndexResult buildStats(ChunkingStrategy strategy, List<RagChunk> chunks) {
        if (chunks.isEmpty()) {
            return new IndexResult(strategy, 0, 0, 0, 0, List.of());
        }
        int min = chunks.stream().mapToInt(c -> c.content().length()).min().orElse(0);
        int max = chunks.stream().mapToInt(c -> c.content().length()).max().orElse(0);
        double avg = chunks.stream().mapToInt(c -> c.content().length()).average().orElse(0);
        List<ChunkSample> samples = chunks.stream()
                .limit(3)
                .map(c -> new ChunkSample(c.chunkId(), c.section(), c.content().substring(0, Math.min(120, c.content().length()))))
                .toList();
        return new IndexResult(strategy, chunks.size(), avg, min, max, samples);
    }

    public record IndexResult(
            ChunkingStrategy strategy,
            int chunkCount,
            double avgChunkSize,
            int minChunkSize,
            int maxChunkSize,
            List<ChunkSample> samples) {
    }

    public record ChunkSample(String chunkId, String section, String preview) {
    }

    public record CompareResult(IndexResult fixedSize, IndexResult structure) {
    }
}
