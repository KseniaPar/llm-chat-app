package com.example.llmchat.rag;

import com.example.llmchat.dto.RagEvalQuestionDto;
import com.example.llmchat.dto.RagEvalResponse;
import com.example.llmchat.dto.RagEvalResultDto;
import com.example.llmchat.dto.RagModeEvalResponse;
import com.example.llmchat.dto.RagModeEvalResultDto;
import com.example.llmchat.dto.RagQueryCompareResponse;
import com.example.llmchat.dto.RagQueryResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class RagEvalService {

    private final RagEvalQuestionLoader questionLoader;
    private final RagQueryService queryService;

    public RagEvalService(RagEvalQuestionLoader questionLoader, RagQueryService queryService) {
        this.questionLoader = questionLoader;
        this.queryService = queryService;
    }

    public List<RagEvalQuestionDto> questions() {
        return questionLoader.loadAll();
    }

    public RagQueryCompareResponse compareOne(String question, ChunkingStrategy strategy, Integer topK) {
        return queryService.compare(question, strategy, topK);
    }

    public RagEvalResponse runEval(ChunkingStrategy strategy, Integer topK) {
        List<RagEvalQuestionDto> questions = questionLoader.loadAll();
        List<RagEvalResultDto> results = new ArrayList<>();
        int ragWithSources = 0;

        for (RagEvalQuestionDto question : questions) {
            RagQueryCompareResponse compared = queryService.compare(question.question(), strategy, topK);
            List<String> matched = matchSources(question, compared.withRag());
            if (!matched.isEmpty()) {
                ragWithSources++;
            }
            results.add(new RagEvalResultDto(
                    question,
                    compared.withoutRag(),
                    compared.withRag(),
                    matched));
        }

        return new RagEvalResponse(
                questions.size(),
                ragWithSources,
                questions.size() - ragWithSources,
                results);
    }

    public RagModeEvalResponse runModeEval(ChunkingStrategy strategy, Integer topK, Double minSimilarity) {
        List<RagEvalQuestionDto> questions = questionLoader.loadAll();
        List<RagModeEvalResultDto> results = new ArrayList<>();
        int rawWithSources = 0;
        int filteredWithSources = 0;
        int rewriteFilteredWithSources = 0;

        for (RagEvalQuestionDto question : questions) {
            var compared = queryService.compareModes(question.question(), strategy, topK, minSimilarity);
            List<String> rawMatched = matchSources(question, compared.raw().response());
            List<String> filteredMatched = matchSources(question, compared.filtered().response());
            List<String> rewriteMatched = matchSources(question, compared.rewriteFiltered().response());

            if (!rawMatched.isEmpty()) {
                rawWithSources++;
            }
            if (!filteredMatched.isEmpty()) {
                filteredWithSources++;
            }
            if (!rewriteMatched.isEmpty()) {
                rewriteFilteredWithSources++;
            }

            results.add(new RagModeEvalResultDto(
                    question,
                    compared.raw().retrieval().topKAfter(),
                    compared.filtered().retrieval().topKAfter(),
                    compared.rewriteFiltered().retrieval().topKAfter(),
                    rawMatched,
                    filteredMatched,
                    rewriteMatched));
        }

        return new RagModeEvalResponse(
                questions.size(),
                rawWithSources,
                filteredWithSources,
                rewriteFilteredWithSources,
                results);
    }

    private List<String> matchSources(RagEvalQuestionDto question, RagQueryResponse withRag) {
        List<String> matched = new ArrayList<>();
        String answerLower = withRag.answer().toLowerCase(Locale.ROOT);
        for (String source : question.expectedSources()) {
            if (answerLower.contains(source.toLowerCase(Locale.ROOT))) {
                matched.add(source);
            }
        }
        for (RagQueryResponse.ChunkUsedDto chunk : withRag.chunksUsed()) {
            String haystack = (chunk.section() + " " + chunk.preview()).toLowerCase(Locale.ROOT);
            for (String source : question.expectedSources()) {
                if (haystack.contains(source.toLowerCase(Locale.ROOT)) && !matched.contains(source)) {
                    matched.add(source);
                }
            }
        }
        return matched;
    }
}
