package com.example.llmchat.fileassist;

import java.util.List;
import java.util.Objects;

final class UnifiedDiff {

    private UnifiedDiff() {
    }

    static String build(String path, String oldContent, String newContent) {
        List<String> oldLines = lines(oldContent);
        List<String> newLines = lines(newContent);
        StringBuilder diff = new StringBuilder();
        diff.append("--- a/").append(path).append('\n');
        diff.append("+++ b/").append(path).append('\n');
        int max = Math.max(oldLines.size(), newLines.size());
        boolean header = false;
        for (int i = 0; i < max; i++) {
            String oldLine = i < oldLines.size() ? oldLines.get(i) : null;
            String newLine = i < newLines.size() ? newLines.get(i) : null;
            if (!Objects.equals(oldLine, newLine)) {
                if (!header) {
                    diff.append("@@ changes @@\n");
                    header = true;
                }
                if (oldLine != null) {
                    diff.append('-').append(oldLine).append('\n');
                }
                if (newLine != null) {
                    diff.append('+').append(newLine).append('\n');
                }
            }
        }
        if (!header) {
            diff.append("@@ no changes @@\n");
        }
        return diff.toString();
    }

    private static List<String> lines(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        return content.lines().toList();
    }
}
