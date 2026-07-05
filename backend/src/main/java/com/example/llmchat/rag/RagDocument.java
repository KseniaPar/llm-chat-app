package com.example.llmchat.rag;

public record RagDocument(
        String sourcePath,
        String title,
        String docType,
        String content) {
}
