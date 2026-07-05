package com.example.llmchat.dto;

import com.example.llmchat.rag.RagIndexingService;

public record RagCompareResponse(
        RagIndexResponse fixedSize,
        RagIndexResponse structure) {

    public static RagCompareResponse from(RagIndexingService.CompareResult result) {
        return new RagCompareResponse(
                RagIndexResponse.from(result.fixedSize()),
                RagIndexResponse.from(result.structure()));
    }
}
