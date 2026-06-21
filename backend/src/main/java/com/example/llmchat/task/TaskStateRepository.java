package com.example.llmchat.task;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public class TaskStateRepository {

    private static final RowMapper<TaskState> ROW_MAPPER = (rs, rowNum) -> new TaskState(
            rs.getString("session_id"),
            TaskPhase.fromId(rs.getString("phase")),
            rs.getString("current_step"),
            rs.getString("expected_action"),
            rs.getInt("paused") != 0,
            rs.getString("task_title"),
            Instant.parse(rs.getString("updated_at")));

    private final JdbcTemplate jdbcTemplate;

    public TaskStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<TaskState> findBySessionId(String sessionId) {
        try {
            TaskState state = jdbcTemplate.queryForObject(
                    """
                            SELECT session_id, phase, current_step, expected_action, paused, task_title, updated_at
                            FROM session_task_state WHERE session_id = ?
                            """,
                    ROW_MAPPER,
                    sessionId);
            return Optional.ofNullable(state);
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public TaskState upsert(TaskState state) {
        String now = Instant.now().toString();
        int updated = jdbcTemplate.update(
                """
                        UPDATE session_task_state
                        SET phase = ?, current_step = ?, expected_action = ?, paused = ?, task_title = ?, updated_at = ?
                        WHERE session_id = ?
                        """,
                state.phase().id(),
                state.currentStep(),
                state.expectedAction(),
                state.paused() ? 1 : 0,
                state.taskTitle(),
                now,
                state.sessionId());
        if (updated == 0) {
            jdbcTemplate.update(
                    """
                            INSERT INTO session_task_state
                            (session_id, phase, current_step, expected_action, paused, task_title, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """,
                    state.sessionId(),
                    state.phase().id(),
                    state.currentStep(),
                    state.expectedAction(),
                    state.paused() ? 1 : 0,
                    state.taskTitle(),
                    now);
        }
        return findBySessionId(state.sessionId()).orElse(state);
    }

    public void deleteBySessionId(String sessionId) {
        jdbcTemplate.update("DELETE FROM session_task_state WHERE session_id = ?", sessionId);
    }
}
