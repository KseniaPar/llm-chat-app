package com.example.llmchat.rag;

import com.example.llmchat.dto.RagChatMessageDto;
import com.example.llmchat.dto.RagChatRequest;
import com.example.llmchat.dto.RagChatResponse;
import com.example.llmchat.dto.RagDialogMemoryDto;
import com.example.llmchat.dto.RagQueryResponse;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RagChatService {

    private final RagChatSessionStore sessionStore;
    private final RagQueryService queryService;
    private final RagDialogMemoryUpdater memoryUpdater;

    public RagChatService(
            RagChatSessionStore sessionStore,
            RagQueryService queryService,
            RagDialogMemoryUpdater memoryUpdater) {
        this.sessionStore = sessionStore;
        this.queryService = queryService;
        this.memoryUpdater = memoryUpdater;
    }

    public RagChatResponse chat(RagChatRequest request) {
        RagChatSession session = sessionStore.getOrCreate(request.sessionId());
        session.append(RagChatMessageDto.user(request.message()));

        List<RagChatMessageDto> historyBeforeAssistant = session.messages();
        RagQueryResponse ragResponse = queryService.queryChatTurn(
                request.message(),
                historyBeforeAssistant.subList(0, historyBeforeAssistant.size() - 1),
                session.taskMemory(),
                request.strategy() != null ? request.strategy() : ChunkingStrategy.STRUCTURE,
                request.topK(),
                request.mode(),
                request.minSimilarity(),
                request.llmProvider());

        RagChatMessageDto assistantMessage = RagChatMessageDto.assistant(
                ragResponse.answer(),
                ragResponse.sources(),
                ragResponse.quotes(),
                ragResponse.confidence());
        session.append(assistantMessage);

        RagDialogMemoryDto updatedMemory = memoryUpdater.updateFromTurn(
                session.taskMemory(),
                request.message(),
                ragResponse.answer(),
                session.messages());
        session.setTaskMemory(updatedMemory);

        return new RagChatResponse(
                session.sessionId(),
                assistantMessage,
                session.messages(),
                session.taskMemory());
    }

    public List<RagChatMessageDto> history(String sessionId) {
        return sessionStore.find(sessionId)
                .map(RagChatSession::messages)
                .orElse(List.of());
    }

    public RagDialogMemoryDto memory(String sessionId) {
        return sessionStore.find(sessionId)
                .map(RagChatSession::taskMemory)
                .orElse(RagDialogMemoryDto.empty());
    }

    public String reset(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            sessionStore.reset(sessionId);
            return sessionId;
        }
        return sessionStore.createSession();
    }
}
