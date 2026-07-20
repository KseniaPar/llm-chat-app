package com.example.llmchat.exam;

public record ExamTranscriptSegment(
        int index,
        double startSec,
        double endSec,
        String text) {

    public String timestampLabel() {
        return ExamTimeFormat.formatRange(startSec, endSec);
    }
}
