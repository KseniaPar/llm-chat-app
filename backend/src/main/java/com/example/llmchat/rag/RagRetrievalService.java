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
    private static final int STRONG_KEYWORD_MIN_LENGTH = 5;
    private static final Set<String> STOP_WORDS = Set.of(
            "что", "такое", "такая", "такие", "этот", "этого", "этой", "эта", "это",
            "какой", "какая", "какие", "какое", "когда", "где", "кто", "чем", "чему",
            "как", "для", "при", "или", "либо", "если", "тоже", "также", "быть",
            "было", "были", "будет", "может", "можно", "нужно", "надо", "ли",
            "из", "от", "до", "над", "под", "про", "без", "через",
            "кратко", "назовите", "перечислите", "изложите", "опишите", "объясните",
            "скажите", "расскажите", "дайте", "укажите", "напишите", "определите",
            "основы", "учение", "православ", "православие", "православной", "православная", "православное",
            "церковь", "церкви", "христиан", "христианство", "история", "смысл",
            "святой", "святую", "святая", "святых", "троица", "троице", "троицу");

    private final EmbeddingService embeddingService;
    private final RagIndexRepository indexRepository;
    private final QueryRewriteService queryRewriteService;
    private final RagChunkQualityFilter chunkQualityFilter;
    private final int defaultTopK;
    private final int searchPoolSize;
    private final double defaultMinSimilarity;
    private final double keywordFilterFloor;

    public RagRetrievalService(
            EmbeddingService embeddingService,
            RagIndexRepository indexRepository,
            QueryRewriteService queryRewriteService,
            RagChunkQualityFilter chunkQualityFilter,
            @Value("${app.rag.default-top-k:5}") int defaultTopK,
            @Value("${app.rag.search-pool-size:20}") int searchPoolSize,
            @Value("${app.rag.min-similarity:0.65}") double defaultMinSimilarity,
            @Value("${app.rag.keyword-filter-floor:0.18}") double keywordFilterFloor) {
        this.embeddingService = embeddingService;
        this.indexRepository = indexRepository;
        this.queryRewriteService = queryRewriteService;
        this.chunkQualityFilter = chunkQualityFilter;
        this.defaultTopK = defaultTopK;
        this.searchPoolSize = searchPoolSize;
        this.defaultMinSimilarity = defaultMinSimilarity;
        this.keywordFilterFloor = keywordFilterFloor;
    }

    public List<ScoredChunk> search(String query, ChunkingStrategy strategy, Integer topK) {
        return retrieve(query, strategy, RagRetrievalMode.RAW, topK, null).chunks();
    }

    public RetrievalResult retrieve(
            String originalQuery,
            ChunkingStrategy strategy,
            RagRetrievalMode mode,
            Integer topK,
            Double minSimilarity) {
        RagRetrievalMode effectiveMode = mode != null ? mode : RagRetrievalMode.RAW;
        int k = topK != null ? topK : defaultTopK;
        double threshold = minSimilarity != null ? minSimilarity : defaultMinSimilarity;
        int poolSize = Math.max(k, searchPoolSize);

        String rewrittenQuery = null;
        String searchQuery = originalQuery;
        if (effectiveMode == RagRetrievalMode.REWRITE_FILTERED) {
            rewrittenQuery = queryRewriteService.rewrite(originalQuery);
            searchQuery = rewrittenQuery;
        }

        List<String> keywords = extractKeywords(searchQuery);
        List<ScoredChunk> ranked = scoreAll(searchQuery, strategy, keywords);
        List<ScoredChunk> pool = ranked.stream().limit(poolSize).toList();
        List<Double> scoresBefore = pool.stream().map(ScoredChunk::semanticScore).toList();

        if (effectiveMode == RagRetrievalMode.RAW) {
            List<ScoredChunk> selected = pool.stream().limit(k).toList();
            return new RetrievalResult(
                    selected,
                    originalQuery,
                    rewrittenQuery,
                    searchQuery,
                    effectiveMode,
                    pool.size(),
                    selected.size(),
                    0,
                    threshold,
                    scoresBefore,
                    selected.stream().map(ScoredChunk::semanticScore).toList());
        }

        List<ScoredChunk> passing = pool.stream()
                .filter(chunk -> passesFilter(chunk, keywords, threshold))
                .toList();
        List<ScoredChunk> filtered = passing.stream().limit(k).toList();
        int dropped = pool.size() - passing.size();

        return new RetrievalResult(
                filtered,
                originalQuery,
                rewrittenQuery,
                searchQuery,
                effectiveMode,
                pool.size(),
                filtered.size(),
                dropped,
                threshold,
                scoresBefore,
                filtered.stream().map(ScoredChunk::semanticScore).toList());
    }

    private boolean passesFilter(ScoredChunk chunk, List<String> keywords, double threshold) {
        if (chunk.semanticScore() >= threshold) {
            return true;
        }
        boolean rareKeywordHit = keywords.stream()
                .filter(keyword -> keyword.length() >= 7)
                .anyMatch(keyword -> chunk.content().toLowerCase(Locale.ROOT).contains(keyword));
        return rareKeywordHit && chunk.semanticScore() >= keywordFilterFloor;
    }

    private List<ScoredChunk> scoreAll(String query, ChunkingStrategy strategy, List<String> keywords) {
        float[] queryVector = embeddingService.embed(query);
        List<RagIndexRepository.IndexedChunk> chunks = indexRepository.loadChunks(strategy);
        if (chunks.isEmpty()) {
            throw new IllegalStateException("Индекс пуст — сначала выполните POST /api/rag/index");
        }
        List<ScoredChunk> scored = new ArrayList<>();
        for (RagIndexRepository.IndexedChunk chunk : chunks) {
            if (chunkQualityFilter.isBibliographyOrNavigation(chunk.section(), chunk.content())) {
                continue;
            }
            double semanticScore = EmbeddingService.cosineSimilarity(queryVector, chunk.embedding());
            double rankScore = applyKeywordBoost(semanticScore, chunk.content(), keywords);
            scored.add(new ScoredChunk(chunk, semanticScore, rankScore));
        }
        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return scored;
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

    private static int countStrongKeywordMatches(String content, List<String> keywords) {
        if (content == null || content.isBlank() || keywords.isEmpty()) {
            return 0;
        }
        String haystack = content.toLowerCase(Locale.ROOT);
        int matches = 0;
        for (String keyword : keywords) {
            if (keyword.length() >= STRONG_KEYWORD_MIN_LENGTH && haystack.contains(keyword)) {
                matches++;
            }
        }
        return matches;
    }

    private static double applyKeywordBoost(double semanticScore, String content, List<String> keywords) {
        int matches = countStrongKeywordMatches(content, keywords);
        if (matches == 0) {
            return semanticScore;
        }
        double keywordScore = 0.85 + Math.min(0.14, 0.05 * matches);
        return Math.max(semanticScore, keywordScore);
    }

    public record RetrievalResult(
            List<ScoredChunk> chunks,
            String originalQuery,
            String rewrittenQuery,
            String searchQuery,
            RagRetrievalMode mode,
            int topKBefore,
            int topKAfter,
            int droppedCount,
            double minSimilarity,
            List<Double> scoresBefore,
            List<Double> scoresAfter) {
    }

    public record ScoredChunk(RagIndexRepository.IndexedChunk chunk, double semanticScore, double score) {
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
