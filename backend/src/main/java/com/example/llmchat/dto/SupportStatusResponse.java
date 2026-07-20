package com.example.llmchat.dto;

public record SupportStatusResponse(
        String platformVersion,
        boolean llmReady,
        String llmModel,
        boolean mcpConnected,
        boolean ticketsToolAvailable,
        int ticketCount,
        boolean supportIndexReady,
        int supportDocuments,
        int supportChunks,
        String supportIndexPath,
        String ticketsDir) {
}
