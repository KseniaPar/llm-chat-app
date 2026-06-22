package com.example.llmchat.task;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class TaskTransitionRepository {

    private static final RowMapper<TaskTransitionRecord> ROW_MAPPER = (rs, rowNum) -> {
        String rejectionCodeText = rs.getString("rejection_code");
        TaskTransitionRejectionCode rejectionCode = rejectionCodeText != null && !rejectionCodeText.isBlank()
                ? TaskTransitionRejectionCode.valueOf(rejectionCodeText)
                : null;
        String fromPhaseText = rs.getString("from_phase");
        String toPhaseText = rs.getString("to_phase");
        return new TaskTransitionRecord(
                rs.getLong("id"),
                rs.getString("session_id"),
                TaskTransitionType.valueOf(rs.getString("transition_type")),
                fromPhaseText != null && !fromPhaseText.isBlank() ? TaskPhase.fromId(fromPhaseText) : null,
                toPhaseText != null && !toPhaseText.isBlank() ? TaskPhase.fromId(toPhaseText) : null,
                rs.getString("from_step"),
                rs.getString("to_step"),
                TaskTransitionTriggerSource.valueOf(rs.getString("trigger_source")),
                rs.getInt("accepted") != 0,
                rejectionCode,
                rs.getString("rejection_reason"),
                Instant.parse(rs.getString("created_at")));
    };

    private final JdbcTemplate jdbcTemplate;

    public TaskTransitionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(
            String sessionId,
            TaskTransitionType transitionType,
            TaskPhase fromPhase,
            TaskPhase toPhase,
            String fromStep,
            String toStep,
            TaskTransitionTriggerSource triggerSource,
            boolean accepted,
            TaskTransitionRejectionCode rejectionCode,
            String rejectionReason) {
        jdbcTemplate.update(
                """
                        INSERT INTO session_task_transitions
                        (session_id, transition_type, from_phase, to_phase, from_step, to_step,
                         trigger_source, accepted, rejection_code, rejection_reason, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                sessionId,
                transitionType.name(),
                fromPhase != null ? fromPhase.id() : null,
                toPhase != null ? toPhase.id() : null,
                fromStep,
                toStep,
                triggerSource.name(),
                accepted ? 1 : 0,
                rejectionCode != null ? rejectionCode.name() : null,
                rejectionReason,
                Instant.now().toString());
    }

    public List<TaskTransitionRecord> listBySessionId(String sessionId, int limit) {
        return jdbcTemplate.query(
                """
                        SELECT id, session_id, transition_type, from_phase, to_phase, from_step, to_step,
                               trigger_source, accepted, rejection_code, rejection_reason, created_at
                        FROM session_task_transitions
                        WHERE session_id = ?
                        ORDER BY id DESC
                        LIMIT ?
                        """,
                ROW_MAPPER,
                sessionId,
                limit);
    }

    public void deleteBySessionId(String sessionId) {
        jdbcTemplate.update("DELETE FROM session_task_transitions WHERE session_id = ?", sessionId);
    }
}
