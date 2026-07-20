package com.example.llmchat.dto;

public record ExamCitationDto(
        String lecture,
        String timestamp,
        String quote,
        double score) {
}
