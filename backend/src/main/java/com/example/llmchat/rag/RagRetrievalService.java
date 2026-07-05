package com.example.llmchat.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RagRetrievalService {

    private static final Pattern KEYWORD = Pattern.compile("[\\p{IsAlphabetic}]{4,}");
    private static final Set<String> STOP_WORDS = Set.of(
            "что", "такое", "такая", "такие", "этот", "этого", "этой", "эта", "это",
            "какой", "какая", "какие", "какое", "когда", "где", "кто", "чем", "чему",
            "как", "для", "при", "или", "либо", "если", "тоже", "также", "быть",
            "было", "были", "будет", "может", "можно", "нужно", "надо", "ли",
            "из", "от", "до", "над", "под", "про", "без", "через",
            "кратко", "назовите", "перечислите", "изложите", "опишите", "объясните",
            "скажите", "расскажите", "дайте", "укажите", "напишите", "определите");

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
        List<String> keywords = extractKeywords(query);
        List<ScoredChunk> scored = new ArrayList<>();
        for (RagIndexRepository.IndexedChunk chunk : chunks) {
            double score = EmbeddingService.cosineSimilarity(queryVector, chunk.embedding());
            score = applyKeywordBoost(score, chunk.content(), keywords);
            scored.add(new ScoredChunk(chunk, score));
        }
        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return scored.stream().limit(k).toList();
    }

    private static List<String> extractKeywords(String query) {
        Matcher matcher = KEYWORD.matcher(query.toLowerCase(Locale.ROOT));
        List<String> keywords = new ArrayList<>();
        while (matcher.find()) {
            String word = matcher.group();
            if (!STOP_WORDS.contains(word)) {
                keywords.add(word);
            }
        }
        return keywords;
    }

    private static double applyKeywordBoost(double semanticScore, String content, List<String> keywords) {
        if (keywords.isEmpty() || content == null || content.isBlank()) {
            return semanticScore;
        }
        String haystack = content.toLowerCase(Locale.ROOT);
        int matches = 0;
        for (String keyword : keywords) {
            if (haystack.contains(keyword)) {
                matches++;
            }
        }
        if (matches == 0) {
            return semanticScore;
        }
        double keywordScore = 0.85 + Math.min(0.14, 0.05 * matches);
        return Math.max(semanticScore, keywordScore);
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
