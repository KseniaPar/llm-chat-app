package com.example.llmchat.memory;

import com.example.llmchat.dto.AgentChatMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Repository
public class ShortTermMemoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public ShortTermMemoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void replaceAll(String sessionId, List<StoredMessage> messages) {
        jdbcTemplate.update("DELETE FROM short_term_messages WHERE session_id = ?", sessionId);
        String now = Instant.now().toString();
        int seq = 0;
        for (StoredMessage message : messages) {
            jdbcTemplate.update(
                    """
                            INSERT INTO short_term_messages (session_id, role, content, segment, seq, created_at)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """,
                    sessionId,
                    message.role(),
                    message.content(),
                    message.segment().name(),
                    seq++,
                    now);
        }
    }

    public List<StoredMessage> findAll(String sessionId) {
        return jdbcTemplate.query(
                """
                        SELECT role, content, segment
                        FROM short_term_messages
                        WHERE session_id = ?
                        ORDER BY seq
                        """,
                (rs, rowNum) -> new StoredMessage(
                        rs.getString("role"),
                        rs.getString("content"),
                        MessageSegment.valueOf(rs.getString("segment"))),
                sessionId);
    }

    public List<AgentChatMessage> findContextMessages(String sessionId, int windowSize) {
        List<StoredMessage> all = findAll(sessionId);
        if (all.isEmpty()) {
            return List.of();
        }

        boolean hasBranching = all.stream().anyMatch(message -> message.segment() != MessageSegment.MAIN);
        if (!hasBranching) {
            return toAgentMessages(windowSlice(all, windowSize));
        }

        List<StoredMessage> prefix = all.stream()
                .filter(message -> message.segment() == MessageSegment.PREFIX)
                .toList();
        List<StoredMessage> branch = all.stream()
                .filter(message -> message.segment() == MessageSegment.BRANCH)
                .toList();

        List<StoredMessage> combined = new ArrayList<>(prefix);
        combined.addAll(windowSlice(branch, windowSize));
        return toAgentMessages(combined);
    }

    private static List<StoredMessage> windowSlice(List<StoredMessage> messages, int windowSize) {
        if (messages.size() <= windowSize) {
            return messages;
        }
        return messages.subList(messages.size() - windowSize, messages.size());
    }

    private static List<AgentChatMessage> toAgentMessages(List<StoredMessage> messages) {
        return messages.stream()
                .map(message -> new AgentChatMessage(message.role(), message.content()))
                .toList();
    }

    public record StoredMessage(String role, String content, MessageSegment segment) {
    }
}
