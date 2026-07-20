package com.example.llmchat.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class RagRetrievalService {

    private final EmbeddingService embeddingService;
    private final RagIndexStore indexStore;
    private final QueryRewriteService queryRewriteService;
    private final RagChunkQualityFilter chunkQualityFilter;
    private final int defaultTopK;
    private final int searchPoolSize;
    private final double defaultMinSimilarity;
    private final double keywordFilterFloor;

    public RagRetrievalService(
            EmbeddingService embeddingService,
            RagIndexStore indexStore,
            QueryRewriteService queryRewriteService,
            RagChunkQualityFilter chunkQualityFilter,
            @Value("${app.rag.default-top-k:5}") int defaultTopK,
            @Value("${app.rag.search-pool-size:20}") int searchPoolSize,
            @Value("${app.rag.min-similarity:0.65}") double defaultMinSimilarity,
            @Value("${app.rag.keyword-filter-floor:0.18}") double keywordFilterFloor) {
        this.embeddingService = embeddingService;
        this.indexStore = indexStore;
        this.queryRewriteService = queryRewriteService;
        this.chunkQualityFilter = chunkQualityFilter;
        this.defaultTopK = defaultTopK;
        this.searchPoolSize = searchPoolSize;
        this.defaultMinSimilarity = defaultMinSimilarity;
        this.keywordFilterFloor = keywordFilterFloor;
    }

    public List<ScoredChunk> search(String query, ChunkingStrategy strategy, Integer topK, RagStack stack) {
        return retrieve(query, strategy, RagRetrievalMode.RAW, topK, null, stack).chunks();
    }

    public RetrievalResult retrieve(
            String originalQuery,
            ChunkingStrategy strategy,
            RagRetrievalMode mode,
            Integer topK,
            Double minSimilarity,
            RagStack stack) {
        RagRetrievalMode effectiveMode = mode != null ? mode : RagRetrievalMode.RAW;
        int k = topK != null ? topK : defaultTopK;
        double threshold = minSimilarity != null ? minSimilarity : defaultMinSimilarity;
        int poolSize = Math.max(k, searchPoolSize);

        String rewrittenQuery = null;
        String searchQuery = originalQuery;
        if (effectiveMode == RagRetrievalMode.REWRITE_FILTERED && stack == RagStack.CLOUD) {
            rewrittenQuery = queryRewriteService.rewrite(originalQuery);
            searchQuery = rewrittenQuery;
        }

        List<String> keywords = RagKeywords.merge(originalQuery, searchQuery);
        ScoreAllResult scored = scoreAll(searchQuery, strategy, keywords, stack);
        List<ScoredChunk> ranked = scored.chunks();
        String embeddingSource = scored.embeddingSource();
        List<ScoredChunk> pool = enrichPool(ranked, poolSize, keywords);
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
                    selected.stream().map(ScoredChunk::semanticScore).toList(),
                    embeddingSource);
        }

        List<ScoredChunk> passing = pool.stream()
                .filter(chunk -> passesFilter(chunk, keywords, threshold))
                .sorted(Comparator.comparingDouble(ScoredChunk::semanticScore).reversed())
                .toList();
        List<ScoredChunk> filtered = dedupeOverlapping(passing).stream().limit(k).toList();
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
                filtered.stream().map(ScoredChunk::semanticScore).toList(),
                embeddingSource);
    }

    private boolean passesFilter(ScoredChunk chunk, List<String> keywords, double threshold) {
        if (chunk.semanticScore() >= threshold) {
            return true;
        }
        if (hasStrongKeywordHit(chunk.content(), keywords) && chunk.semanticScore() >= keywordFilterFloor) {
            return true;
        }
        boolean rareKeywordHit = keywords.stream()
                .filter(keyword -> keyword.length() >= 7)
                .anyMatch(keyword -> chunk.content().toLowerCase(Locale.ROOT).contains(keyword));
        return rareKeywordHit && chunk.semanticScore() >= keywordFilterFloor;
    }

    private List<ScoredChunk> enrichPool(List<ScoredChunk> ranked, int poolSize, List<String> keywords) {
        Map<String, ScoredChunk> pool = new LinkedHashMap<>();
        for (ScoredChunk chunk : ranked) {
            if (pool.size() >= poolSize) {
                break;
            }
            pool.put(chunk.chunkId(), chunk);
        }

        List<String> strongTerms = RagKeywords.strongTerms(keywords);
        if (strongTerms.isEmpty()) {
            return List.copyOf(pool.values());
        }

        int maxPool = poolSize + Math.min(strongTerms.size() * 3, 10);
        for (ScoredChunk chunk : ranked) {
            if (pool.size() >= maxPool) {
                break;
            }
            if (pool.containsKey(chunk.chunkId())) {
                continue;
            }
            if (hasStrongKeywordHit(chunk.content(), strongTerms) && chunk.semanticScore() >= keywordFilterFloor) {
                pool.put(chunk.chunkId(), chunk);
            }
        }
        return List.copyOf(pool.values());
    }

    private static boolean hasStrongKeywordHit(String content, List<String> keywords) {
        if (content == null || content.isBlank() || keywords.isEmpty()) {
            return false;
        }
        String haystack = content.toLowerCase(Locale.ROOT);
        return keywords.stream().anyMatch(haystack::contains);
    }

    private List<ScoredChunk> dedupeOverlapping(List<ScoredChunk> chunks) {
        List<ScoredChunk> unique = new ArrayList<>();
        Set<String> seenSections = new HashSet<>();
        Set<String> seenContent = new HashSet<>();
        for (ScoredChunk chunk : chunks) {
            String sectionKey = sectionKey(chunk);
            if (seenSections.contains(sectionKey)) {
                continue;
            }
            String normalized = normalizeContent(chunk.content());
            if (normalized.length() >= 80) {
                String prefix = normalized.substring(0, Math.min(120, normalized.length()));
                if (seenContent.contains(prefix)) {
                    continue;
                }
                seenContent.add(prefix);
            }
            seenSections.add(sectionKey);
            unique.add(chunk);
        }
        return unique;
    }

    private static String sectionKey(ScoredChunk chunk) {
        if (chunk.section() != null && !chunk.section().isBlank()) {
            return chunk.section().trim().toLowerCase(Locale.ROOT);
        }
        return chunk.chunkId();
    }

    private static String normalizeContent(String content) {
        return content == null ? "" : content.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private ScoreAllResult scoreAll(String query, ChunkingStrategy strategy, List<String> keywords, RagStack stack) {
        EmbeddingService.EmbedResult embedResult = embeddingService.embedForQuery(query, stack);
        boolean keywordOnly = embedResult.source() == EmbeddingService.EmbeddingSource.KEYWORD_FALLBACK;
        float[] queryVector = embedResult.vector();

        RagIndexRepository indexRepository = indexStore.forStack(stack);
        List<RagIndexRepository.IndexedChunk> chunks = indexRepository.loadChunks(strategy);
        if (chunks.isEmpty()) {
            throw new IllegalStateException("Индекс пуст — сначала выполните POST /api/rag/index");
        }
        List<ScoredChunk> scored = new ArrayList<>();
        for (RagIndexRepository.IndexedChunk chunk : chunks) {
            if (chunkQualityFilter.isBibliographyOrNavigation(chunk.section(), chunk.content())) {
                continue;
            }
            double semanticScore = keywordOnly
                    ? keywordOnlyScore(chunk.content(), keywords)
                    : applyKeywordBoost(
                            EmbeddingService.cosineSimilarity(queryVector, chunk.embedding()),
                            chunk.content(),
                            keywords);
            scored.add(new ScoredChunk(chunk, semanticScore, semanticScore));
        }
        scored.sort(Comparator.comparingDouble(ScoredChunk::semanticScore).reversed());
        return new ScoreAllResult(scored, embedResult.source().name());
    }

    private record ScoreAllResult(List<ScoredChunk> chunks, String embeddingSource) {
    }

    private static double keywordOnlyScore(String content, List<String> keywords) {
        int matches = RagKeywords.countMatches(content, keywords);
        if (matches == 0) {
            return 0.05;
        }
        return Math.min(0.92, 0.45 + 0.12 * matches);
    }

    private static double applyKeywordBoost(double semanticScore, String content, List<String> keywords) {
        int matches = RagKeywords.countMatches(content, keywords);
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
            List<Double> scoresAfter,
            String embeddingSource) {
    }

    public record ScoredChunk(RagIndexRepository.IndexedChunk chunk, double semanticScore, double score) {
        public String chunkId() {
            return chunk.chunkId();
        }

        public String source() {
            return chunk.source();
        }

        public String title() {
            return chunk.title();
        }

        public String section() {
            return chunk.section();
        }

        public String content() {
            return chunk.content();
        }
    }
}
