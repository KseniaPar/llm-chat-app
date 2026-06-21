package com.example.llmchat.memory;

import com.example.llmchat.agent.ContextStrategy;
import com.example.llmchat.agent.SessionState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Repository
public class SqliteSessionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SqliteSessionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void upsert(String sessionId, String userId, SessionState state) {
        String now = Instant.now().toString();
        String strategy = state.getContextStrategy() != null ? state.getContextStrategy().name() : null;
        String stateJson = serializeState(state);

        int updated = jdbcTemplate.update(
                """
                        UPDATE sessions
                        SET user_id = ?, context_strategy = ?, state_json = ?, updated_at = ?
                        WHERE id = ?
                        """,
                userId, strategy, stateJson, now, sessionId);
        if (updated == 0) {
            jdbcTemplate.update(
                    """
                            INSERT INTO sessions (id, user_id, context_strategy, state_json, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """,
                    sessionId, userId, strategy, stateJson, now, now);
        }
    }

    public Optional<SessionRecord> findById(String sessionId) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, user_id, context_strategy, state_json FROM sessions WHERE id = ?",
                    (rs, rowNum) -> new SessionRecord(
                            rs.getString("id"),
                            rs.getString("user_id"),
                            rs.getString("context_strategy"),
                            rs.getString("state_json")),
                    sessionId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public Map<String, SessionState> loadAll() {
        return jdbcTemplate.query(
                "SELECT id, user_id, context_strategy, state_json FROM sessions",
                rs -> {
                    Map<String, SessionState> sessions = new LinkedHashMap<>();
                    while (rs.next()) {
                        String sessionId = rs.getString("id");
                        SessionState state = deserializeState(rs.getString("state_json"));
                        String strategy = rs.getString("context_strategy");
                        if (strategy != null && !strategy.isBlank()) {
                            state.setContextStrategy(ContextStrategy.valueOf(strategy));
                        }
                        sessions.put(sessionId, state);
                    }
                    return sessions;
                });
    }

    public void delete(String sessionId) {
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", sessionId);
    }

    public boolean belongsToUser(String sessionId, String userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sessions WHERE id = ? AND user_id = ?",
                Integer.class,
                sessionId, userId);
        return count != null && count > 0;
    }

    private String serializeState(SessionState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception exception) {
            throw new IllegalStateException("Не удалось сериализовать состояние сессии", exception);
        }
    }

    private SessionState deserializeState(String json) {
        try {
            SessionState state = objectMapper.readValue(json, SessionState.class);
            if (state.getMessages() == null) {
                state.setMessages(java.util.List.of());
            }
            if (state.getFacts() == null) {
                state.setFacts(new java.util.LinkedHashMap<>());
            }
            return state;
        } catch (Exception exception) {
            return new SessionState();
        }
    }

    public record SessionRecord(String id, String userId, String contextStrategy, String stateJson) {
    }
}
