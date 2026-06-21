package com.example.llmchat.memory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Repository
public class WorkingMemoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public WorkingMemoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void replaceFacts(String sessionId, Map<String, String> facts) {
        jdbcTemplate.update("DELETE FROM working_memory WHERE session_id = ?", sessionId);
        if (facts == null || facts.isEmpty()) {
            return;
        }
        String now = Instant.now().toString();
        for (Map.Entry<String, String> entry : facts.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            jdbcTemplate.update(
                    """
                            INSERT INTO working_memory (session_id, key, value, updated_at)
                            VALUES (?, ?, ?, ?)
                            """,
                    sessionId, entry.getKey(), entry.getValue(), now);
        }
    }

    public Map<String, String> findFacts(String sessionId) {
        return jdbcTemplate.query(
                "SELECT key, value FROM working_memory WHERE session_id = ? ORDER BY key",
                rs -> {
                    Map<String, String> facts = new LinkedHashMap<>();
                    while (rs.next()) {
                        facts.put(rs.getString("key"), rs.getString("value"));
                    }
                    return facts;
                },
                sessionId);
    }

    public void upsertSummary(String sessionId, String summary) {
        if (summary == null || summary.isBlank()) {
            jdbcTemplate.update("DELETE FROM working_summary WHERE session_id = ?", sessionId);
            return;
        }
        String now = Instant.now().toString();
        int updated = jdbcTemplate.update(
                "UPDATE working_summary SET summary = ?, updated_at = ? WHERE session_id = ?",
                summary, now, sessionId);
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO working_summary (session_id, summary, updated_at) VALUES (?, ?, ?)",
                    sessionId, summary, now);
        }
    }

    public String findSummary(String sessionId) {
        return jdbcTemplate.query(
                "SELECT summary FROM working_summary WHERE session_id = ?",
                rs -> rs.next() ? rs.getString("summary") : null,
                sessionId);
    }
}
