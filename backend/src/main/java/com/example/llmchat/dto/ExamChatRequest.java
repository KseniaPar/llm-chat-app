package com.example.llmchat.dto;

public record ExamChatRequest(
        String question,
        String lectureTitle) {
}
