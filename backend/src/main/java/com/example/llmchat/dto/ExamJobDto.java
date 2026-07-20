package com.example.llmchat.dto;

import com.example.llmchat.exam.ExamJobStatus;
import com.example.llmchat.exam.ExamTranscriptSegment;

import java.time.Instant;
import java.util.List;

public record ExamJobDto(
        String id,
        String title,
        String subject,
        String originalFilename,
        ExamJobStatus status,
        String message,
        String language,
        double durationSec,
        int segmentCount,
        List<ExamTranscriptSegment> segments,
        String transcriptPath,
        String cleanPath,
        String notesPath,
        Instant createdAt,
        Instant updatedAt) {
}
