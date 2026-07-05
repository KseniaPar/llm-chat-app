package com.example.llmchat.dto;

import com.example.llmchat.rag.ChunkingStrategy;

public record RagIndexRequest(ChunkingStrategy strategy) {
}
