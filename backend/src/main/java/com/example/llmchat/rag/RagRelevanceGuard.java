package com.example.llmchat.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RagRelevanceGuard {

    public static final String UNKNOWN_ANSWER = """
            Не знаю, в базе нет достаточной информации по этому вопросу. \
            Уточните формулировку или задайте более конкретный вопрос по материалу «Основы православия».""";

    private final double minConfidenceScore;

    public RagRelevanceGuard(@Value("${app.rag.min-confidence-score:0.55}") double minConfidenceScore) {
        this.minConfidenceScore = minConfidenceScore;
    }

    public RagConfidence assess(RagRetrievalService.RetrievalResult retrieval) {
        if (retrieval.chunks().isEmpty()) {
            return RagConfidence.UNKNOWN;
        }
        double maxScore = retrieval.chunks().stream()
                .mapToDouble(RagRetrievalService.ScoredChunk::score)
                .max()
                .orElse(0.0);
        if (maxScore < minConfidenceScore) {
            return RagConfidence.UNKNOWN;
        }
        if (maxScore >= 0.72) {
            return RagConfidence.HIGH;
        }
        if (maxScore >= minConfidenceScore + 0.08) {
            return RagConfidence.MEDIUM;
        }
        return RagConfidence.LOW;
    }

    public boolean shouldRefuse(RagConfidence confidence) {
        return confidence == RagConfidence.UNKNOWN;
    }

    public double minConfidenceScore() {
        return minConfidenceScore;
    }
}
