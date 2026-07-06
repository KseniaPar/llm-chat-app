package com.example.llmchat.dto;

import java.util.List;

public record RagModeEvalResultDto(
        RagEvalQuestionDto question,
        int rawTopKAfter,
        int filteredTopKAfter,
        int rewriteFilteredTopKAfter,
        List<String> rawSourcesMatched,
        List<String> filteredSourcesMatched,
        List<String> rewriteFilteredSourcesMatched) {
}
