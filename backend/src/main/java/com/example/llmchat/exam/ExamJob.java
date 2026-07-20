package com.example.llmchat.exam;

import java.time.Instant;
import java.util.List;

public record ExamJob(
        String id,
        String title,
        String subject,
        String originalFilename,
        String audioPath,
        String transcriptPath,
        String cleanPath,
        String notesPath,
        ExamJobStatus status,
        String message,
        String language,
        double durationSec,
        List<ExamTranscriptSegment> segments,
        Instant createdAt,
        Instant updatedAt) {

    public ExamJob withStatus(ExamJobStatus newStatus, String newMessage) {
        return new ExamJob(
                id, title, subject, originalFilename, audioPath, transcriptPath, cleanPath, notesPath,
                newStatus, newMessage, language, durationSec, segments, createdAt, Instant.now());
    }

    public ExamJob withProgress(
            ExamJobStatus newStatus,
            String newMessage,
            String language,
            double durationSec,
            List<ExamTranscriptSegment> newSegments,
            String transcriptPath,
            String cleanPath) {
        return new ExamJob(
                id, title, subject, originalFilename, audioPath,
                transcriptPath, cleanPath, notesPath,
                newStatus, newMessage, language, durationSec, newSegments,
                createdAt, Instant.now());
    }

    public ExamJob withNotesPath(String path) {
        return new ExamJob(
                id, title, subject, originalFilename, audioPath, transcriptPath, cleanPath, path,
                status, message, language, durationSec, segments, createdAt, Instant.now());
    }
}
