package com.example.llmchat.rag;

import com.example.llmchat.dto.RagChatMessageDto;
import com.example.llmchat.dto.RagDialogMemoryDto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class RagChatSession {

    private final String sessionId;
    private final Instant createdAt;
    private final List<RagChatMessageDto> messages = new ArrayList<>();
    private RagDialogMemoryDto taskMemory = RagDialogMemoryDto.empty();

    public RagChatSession(String sessionId) {
        this.sessionId = sessionId;
        this.createdAt = Instant.now();
    }

    public String sessionId() {
        return sessionId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<RagChatMessageDto> messages() {
        return List.copyOf(messages);
    }

    public RagDialogMemoryDto taskMemory() {
        return taskMemory;
    }

    public void setTaskMemory(RagDialogMemoryDto taskMemory) {
        this.taskMemory = taskMemory != null ? taskMemory : RagDialogMemoryDto.empty();
    }

    public void append(RagChatMessageDto message) {
        messages.add(message);
    }

    public void reset() {
        messages.clear();
        taskMemory = RagDialogMemoryDto.empty();
    }
}
