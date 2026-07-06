package com.example.llmchat.dto;

import java.util.List;

public record RagChatResponse(
        String sessionId,
        RagChatMessageDto assistantMessage,
        List<RagChatMessageDto> history,
        RagDialogMemoryDto taskMemory) {
}
