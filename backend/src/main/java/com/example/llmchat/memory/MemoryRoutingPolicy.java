package com.example.llmchat.memory;

import com.example.llmchat.agent.SessionState;
import com.example.llmchat.dto.AgentChatMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class MemoryRoutingPolicy {

    public List<ShortTermMemoryRepository.StoredMessage> routeShortTerm(SessionState state) {
        List<ShortTermMemoryRepository.StoredMessage> messages = new ArrayList<>();

        if (state.getContextStrategy() == com.example.llmchat.agent.ContextStrategy.BRANCHING
                && state.getForkMessageIndex() >= 0) {
            for (AgentChatMessage message : state.getSharedPrefix()) {
                messages.add(new ShortTermMemoryRepository.StoredMessage(
                        message.role(), message.content(), MessageSegment.PREFIX));
            }
            for (AgentChatMessage message : state.getBranchMessages()) {
                messages.add(new ShortTermMemoryRepository.StoredMessage(
                        message.role(), message.content(), MessageSegment.BRANCH));
            }
            return messages;
        }

        if (state.getMessages() != null) {
            for (AgentChatMessage message : state.getMessages()) {
                messages.add(new ShortTermMemoryRepository.StoredMessage(
                        message.role(), message.content(), MessageSegment.MAIN));
            }
        }
        return messages;
    }

    public MemoryLayer routeSummary() {
        return MemoryLayer.WORKING;
    }

    public MemoryLayer routeFacts() {
        return MemoryLayer.WORKING;
    }

    public MemoryLayer routeDialogMessage() {
        return MemoryLayer.SHORT;
    }

    public MemoryLayer routeLongTermExtraction() {
        return MemoryLayer.LONG;
    }
}
