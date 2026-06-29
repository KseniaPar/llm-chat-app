package com.example.mcp.scheduler;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SchedulerStore {

    public static final String TASK_REMINDER = "REMINDER";
    public static final String TASK_PERIODIC_SUMMARY = "PERIODIC_SUMMARY";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_CANCELLED = "cancelled";

    private SchedulerStore() {
    }

    public static void ensureSchema(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS scheduled_tasks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    task_type TEXT NOT NULL,
                    message TEXT,
                    interval_minutes INTEGER,
                    next_run TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'active',
                    payload TEXT,
                    created_at TEXT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS task_results (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    task_id INTEGER NOT NULL,
                    ran_at TEXT NOT NULL,
                    result_json TEXT NOT NULL,
                    FOREIGN KEY (task_id) REFERENCES scheduled_tasks(id)
                )
                """);
    }

    public static long scheduleReminder(JdbcTemplate jdbcTemplate, String message, int delayMinutes, String metadata) {
        Instant nextRun = Instant.now().plus(Math.max(1, delayMinutes), ChronoUnit.MINUTES);
        return insertTask(jdbcTemplate, TASK_REMINDER, message, null, nextRun, metadata);
    }

    public static long scheduleReminderSeconds(
            JdbcTemplate jdbcTemplate, String message, int delaySeconds, String metadata) {
        Instant nextRun = Instant.now().plus(Math.max(1, delaySeconds), ChronoUnit.SECONDS);
        return insertTask(jdbcTemplate, TASK_REMINDER, message, null, nextRun, metadata);
    }

    public static long schedulePeriodicSummary(JdbcTemplate jdbcTemplate, int intervalMinutes, String label) {
        int interval = Math.max(1, intervalMinutes);
        Instant nextRun = Instant.now().plus(interval, ChronoUnit.MINUTES);
        return insertTask(jdbcTemplate, TASK_PERIODIC_SUMMARY, label, interval, nextRun, null);
    }

    public static List<Map<String, Object>> listActiveTasks(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.query(
                """
                        SELECT id, task_type, message, interval_minutes, next_run, status, payload, created_at
                        FROM scheduled_tasks
                        WHERE status = 'active'
                        ORDER BY next_run
                        """,
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("taskType", rs.getString("task_type"));
                    row.put("message", rs.getString("message"));
                    row.put("intervalMinutes", rs.getObject("interval_minutes"));
                    row.put("nextRun", rs.getString("next_run"));
                    row.put("status", rs.getString("status"));
                    row.put("payload", rs.getString("payload"));
                    row.put("createdAt", rs.getString("created_at"));
                    return row;
                });
    }

    public static boolean cancelTask(JdbcTemplate jdbcTemplate, long taskId) {
        int updated = jdbcTemplate.update(
                "UPDATE scheduled_tasks SET status = ? WHERE id = ? AND status = 'active'",
                STATUS_CANCELLED,
                taskId);
        return updated > 0;
    }

    public static int cancelAllActiveTasks(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.update(
                "UPDATE scheduled_tasks SET status = ? WHERE status = 'active'",
                STATUS_CANCELLED);
    }

    /** Demo periodic digests should not clutter the notifications panel. */
    public static int cancelActivePeriodicSummaries(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.update(
                "UPDATE scheduled_tasks SET status = ? WHERE status = 'active' AND task_type = ?",
                STATUS_CANCELLED,
                TASK_PERIODIC_SUMMARY);
    }

    public static int deleteAllResults(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.update("DELETE FROM task_results");
    }

    public static Map<String, Object> getSummaryStats(JdbcTemplate jdbcTemplate, Instant since) {
        Instant boundary = since != null ? since : Instant.now().minus(24, ChronoUnit.HOURS);
        String sinceIso = boundary.toString();

        Integer activeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM scheduled_tasks WHERE status = 'active'", Integer.class);
        Integer completedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_results WHERE ran_at >= ?", Integer.class, sinceIso);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("since", sinceIso);
        summary.put("activeTaskCount", activeCount != null ? activeCount : 0);
        summary.put("resultCount", completedCount != null ? completedCount : 0);
        return summary;
    }

    public static Map<String, Object> getSummary(JdbcTemplate jdbcTemplate, Instant since) {
        Instant boundary = since != null ? since : Instant.now().minus(24, ChronoUnit.HOURS);
        String sinceIso = boundary.toString();

        List<Map<String, Object>> results = jdbcTemplate.query(
                """
                        SELECT r.id, r.task_id, r.ran_at, SUBSTR(r.result_json, 1, 2000) AS result_json, t.task_type, t.message
                        FROM task_results r
                        JOIN scheduled_tasks t ON t.id = r.task_id
                        WHERE r.ran_at >= ?
                        ORDER BY r.ran_at DESC
                        LIMIT 50
                        """,
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("resultId", rs.getLong("id"));
                    row.put("taskId", rs.getLong("task_id"));
                    row.put("ranAt", rs.getString("ran_at"));
                    row.put("taskType", rs.getString("task_type"));
                    row.put("message", rs.getString("message"));
                    row.put("resultJson", rs.getString("result_json"));
                    return row;
                },
                sinceIso);

        Map<String, Object> summary = getSummaryStats(jdbcTemplate, since);
        summary.put("results", results);
        return summary;
    }

    public static List<ScheduledTaskRow> findDueTasks(JdbcTemplate jdbcTemplate, Instant now) {
        return jdbcTemplate.query(
                """
                        SELECT id, task_type, message, interval_minutes, payload
                        FROM scheduled_tasks
                        WHERE status = 'active' AND next_run <= ?
                        ORDER BY next_run
                        """,
                (rs, rowNum) -> new ScheduledTaskRow(
                        rs.getLong("id"),
                        rs.getString("task_type"),
                        rs.getString("message"),
                        rs.getObject("interval_minutes") != null ? rs.getInt("interval_minutes") : null,
                        rs.getString("payload")),
                now.toString());
    }

    public static void recordResult(JdbcTemplate jdbcTemplate, long taskId, String resultJson) {
        jdbcTemplate.update(
                "INSERT INTO task_results (task_id, ran_at, result_json) VALUES (?, ?, ?)",
                taskId,
                Instant.now().toString(),
                resultJson);
    }

    public static void markCompleted(JdbcTemplate jdbcTemplate, long taskId) {
        jdbcTemplate.update("UPDATE scheduled_tasks SET status = ? WHERE id = ?", STATUS_COMPLETED, taskId);
    }

    public static void updateNextRun(JdbcTemplate jdbcTemplate, long taskId, Instant nextRun) {
        jdbcTemplate.update("UPDATE scheduled_tasks SET next_run = ? WHERE id = ?", nextRun.toString(), taskId);
    }

    private static long insertTask(
            JdbcTemplate jdbcTemplate,
            String taskType,
            String message,
            Integer intervalMinutes,
            Instant nextRun,
            String metadata) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                            INSERT INTO scheduled_tasks
                            (task_type, message, interval_minutes, next_run, status, payload, created_at)
                            VALUES (?, ?, ?, ?, 'active', ?, ?)
                            """,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, taskType);
            ps.setString(2, message);
            if (intervalMinutes != null) {
                ps.setInt(3, intervalMinutes);
            } else {
                ps.setNull(3, java.sql.Types.INTEGER);
            }
            ps.setString(4, nextRun.toString());
            ps.setString(5, metadata);
            ps.setString(6, Instant.now().toString());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key != null ? key.longValue() : -1L;
    }

    public record ScheduledTaskRow(
            long id, String taskType, String message, Integer intervalMinutes, String payload) {
    }
}
