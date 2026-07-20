package com.example.llmchat.dto;

public record DevAssistStatusResponse(
        String platformVersion,
        boolean llmReady,
        String llmModel,
        String llmProvider,
        boolean mcpConnected,
        int mcpToolCount,
        boolean gitToolAvailable,
        String gitBranch,
        String gitCommit,
        String repoRoot,
        boolean projectIndexReady,
        int projectDocuments,
        int projectChunks,
        String projectIndexPath) {
}
