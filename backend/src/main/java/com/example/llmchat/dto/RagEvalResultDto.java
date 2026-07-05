package com.example.llmchat.dto;

import java.util.List;

public record RagEvalResultDto(
        RagEvalQuestionDto question,
        RagQueryResponse withoutRag,
        RagQueryResponse withRag,
        List<String> sourcesMatched) {
}
