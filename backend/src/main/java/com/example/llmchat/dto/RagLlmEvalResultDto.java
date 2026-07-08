package com.example.llmchat.dto;

import com.example.llmchat.rag.RagLlmProvider;

import java.util.List;

public record RagLlmEvalResultDto(
        RagEvalQuestionDto question,
        RagQueryResponse localResponse,
        RagQueryResponse cloudResponse,
        List<String> localMatchedSources,
        List<String> cloudMatchedSources,
        long retrievalMs) {
}
