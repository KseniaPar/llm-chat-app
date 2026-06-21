package com.example.llmchat.memory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Repository
public class LongTermMemoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public LongTermMemoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsert(String userId, String category, String key, String value, String sourceSessionId) {
        if (userId == null || category == null || key == null || value == null) {
            return;
        }
        String now = Instant.now().toString();
        int updated = jdbcTemplate.update(
                """
                        UPDATE long_term_memory
                        SET value = ?, source_session_id = ?, updated_at = ?
                        WHERE user_id = ? AND category = ? AND key = ?
                        """,
                value, sourceSessionId, now, userId, category, key);
        if (updated == 0) {
            jdbcTemplate.update(
                    """
                            INSERT INTO long_term_memory
                            (user_id, category, key, value, source_session_id, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """,
                    userId, category, key, value, sourceSessionId, now, now);
        }
    }

    public void upsertCategory(String userId, String category, Map<String, String> entries, String sourceSessionId) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            upsert(userId, category, entry.getKey(), entry.getValue(), sourceSessionId);
        }
    }

    public Map<String, Map<String, String>> findGroupedByCategory(String userId) {
        return jdbcTemplate.query(
                """
                        SELECT category, key, value
                        FROM long_term_memory
                        WHERE user_id = ?
                        ORDER BY category, key
                        """,
                rs -> {
                    Map<String, Map<String, String>> grouped = new LinkedHashMap<>();
                    while (rs.next()) {
                        String category = rs.getString("category");
                        grouped.computeIfAbsent(category, ignored -> new LinkedHashMap<>())
                                .put(rs.getString("key"), rs.getString("value"));
                    }
                    return grouped;
                },
                userId);
    }
}
