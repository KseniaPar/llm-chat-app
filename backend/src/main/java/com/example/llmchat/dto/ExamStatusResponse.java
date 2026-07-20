package com.example.llmchat.dto;

public record ExamStatusResponse(
        String platformVersion,
        boolean cloudConfigured,
        String transcriptionModel,
        String chatModel,
        int jobCount,
        int readyJobs,
        int chunkCount,
        String indexPath,
        String audioDir,
        String notesDir) {
}
