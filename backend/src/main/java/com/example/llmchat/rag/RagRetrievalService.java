package com.example.llmchat.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class RagRetrievalService {

    private final EmbeddingService embeddingService;
    private final RagIndexRepository indexRepository;
    private final int defaultTopK;

    public RagRetrievalService(
            EmbeddingService embeddingService,
            RagIndexRepository indexRepository,
            @Value("${app.rag.default-top-k:5}") int defaultTopK) {
        this.embeddingService = embeddingService;
        this.indexRepository = indexRepository;
        this.defaultTopK = defaultTopK;
    }

    public List<ScoredChunk> search(String query, ChunkingStrategy strategy, Integer topK) {
        int k = topK != null ? topK : defaultTopK;
        float[] queryVector = embeddingService.embed(query);
        List<RagIndexRepository.IndexedChunk> chunks = indexRepository.loadChunks(strategy);
        if (chunks.isEmpty()) {
            throw new IllegalStateException("Индекс пуст — сначала выполните POST /api/rag/index");
        }
        List<ScoredChunk> scored = new ArrayList<>();
        for (RagIndexRepository.IndexedChunk chunk : chunks) {
            double score = EmbeddingService.cosineSimilarity(queryVector, chunk.embedding());
            scored.add(new ScoredChunk(chunk, score));
        }
        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return scored.stream().limit(k).toList();
    }

    public record ScoredChunk(RagIndexRepository.IndexedChunk chunk, double score) {
        public String chunkId() {
            return chunk.chunkId();
        }

        public String source() {
            return chunk.source();
        }

        public String section() {
            return chunk.section();
        }

        public String content() {
            return chunk.content();
        }
    }
}
