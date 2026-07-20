package com.example.llmchat.exam;

import java.util.Locale;

public final class ExamTimeFormat {

    private ExamTimeFormat() {
    }

    public static String atTimestamp(double seconds) {
        return formatRange(seconds, seconds);
    }

    public static String formatRange(double startSec, double endSec) {
        return formatClock(startSec) + (endSec > startSec + 0.5 ? "–" + formatClock(endSec) : "");
    }

    public static String formatClock(double seconds) {
        int total = Math.max(0, (int) Math.round(seconds));
        int minutes = total / 60;
        int secs = total % 60;
        return String.format(Locale.ROOT, "%d:%02d", minutes, secs);
    }
}
