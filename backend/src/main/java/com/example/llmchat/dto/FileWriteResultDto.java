package com.example.llmchat.dto;

import java.util.List;

public record FileWriteResultDto(
        String path,
        boolean created,
        boolean dryRun,
        boolean written,
        String unifiedDiff) {
}
