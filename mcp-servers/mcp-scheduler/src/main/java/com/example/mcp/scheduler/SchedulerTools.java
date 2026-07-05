package com.example.mcp.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SchedulerTools {

    private static final Logger log = LoggerFactory.getLogger(SchedulerTools.class);

    private final JdbcTemplate jdbcTemplate;

    public SchedulerTools(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Tool(description = """
            Schedule a one-shot reminder after a delay.
            Use delaySeconds for short delays (e.g. 30), delayMinutes for longer (e.g. 2).
            Use when the user asks to be reminded later, e.g. 'напомни через 30 секунд'.""")
    public Map<String, Object> scheduleReminder(
            @ToolParam(description = "Reminder text shown when the task fires") String message,
            @ToolParam(description = "Delay in minutes (min 1), ignored if delaySeconds is set", required = false)
                    Integer delayMinutes,
            @ToolParam(description = "Delay in seconds (min 1), e.g. 30 for demo", required = false) Integer delaySeconds,
            @ToolParam(description = "Optional JSON metadata", required = false) String metadata) {
        if (message == null || message.isBlank()) {
            return Map.of("success", false, "message", "message is required");
        }
        long taskId;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("taskType", SchedulerStore.TASK_REMINDER);
        result.put("message", message.trim());
        if (delaySeconds != null && delaySeconds > 0) {
            int seconds = Math.max(1, delaySeconds);
            taskId = SchedulerStore.scheduleReminderSeconds(jdbcTemplate, message.trim(), seconds, metadata);
            log.info("scheduleReminder id={} delaySeconds={} message='{}'", taskId, seconds, message.trim());
            result.put("taskId", taskId);
            result.put("delaySeconds", seconds);
        } else {
            int minutes = Math.max(1, delayMinutes != null ? delayMinutes : 1);
            taskId = SchedulerStore.scheduleReminder(jdbcTemplate, message.trim(), minutes, metadata);
            log.info("scheduleReminder id={} delayMinutes={} message='{}'", taskId, minutes, message.trim());
            result.put("taskId", taskId);
            result.put("delayMinutes", minutes);
        }
        return result;
    }

    @Tool(description = """
            Schedule a periodic summary task that aggregates recent scheduler results.
            Use for 24/7 agent summaries at a fixed interval in minutes.""")
    public Map<String, Object> schedulePeriodicSummary(
            @ToolParam(description = "Repeat interval in minutes (minimum 1)") int intervalMinutes,
            @ToolParam(description = "Optional label for the summary task", required = false) String label) {
        String taskLabel = label != null && !label.isBlank() ? label.trim() : "Periodic summary";
        long taskId = SchedulerStore.schedulePeriodicSummary(jdbcTemplate, intervalMinutes, taskLabel);
        log.info("schedulePeriodicSummary id={} intervalMinutes={} label='{}'", taskId, intervalMinutes, taskLabel);
        return Map.of(
                "success", true,
                "taskId", taskId,
                "taskType", SchedulerStore.TASK_PERIODIC_SUMMARY,
                "intervalMinutes", Math.max(1, intervalMinutes),
                "label", taskLabel);
    }

    @Tool(description = "List all active scheduled tasks (reminders and periodic summaries).")
    public Map<String, Object> listScheduledTasks() {
        List<Map<String, Object>> tasks = SchedulerStore.listActiveTasks(jdbcTemplate);
        log.info("listScheduledTasks -> {} active task(s)", tasks.size());
        return Map.of("count", tasks.size(), "tasks", tasks);
    }

    @Tool(description = """
            Get aggregated summary of task results since a given ISO-8601 instant.
            Omit since to default to the last 24 hours.""")
    public Map<String, Object> getSummary(
            @ToolParam(description = "ISO-8601 instant, e.g. 2026-06-28T10:00:00Z", required = false) String since) {
        Instant sinceInstant = null;
        if (since != null && !since.isBlank()) {
            sinceInstant = Instant.parse(since.trim());
        }
        Map<String, Object> summary = SchedulerStore.getSummary(jdbcTemplate, sinceInstant);
        log.info("getSummary since={} -> {} result(s)", summary.get("since"), summary.get("resultCount"));
        return summary;
    }

    @Tool(description = "Cancel an active scheduled task by id.")
    public Map<String, Object> cancelTask(
            @ToolParam(description = "Task id from scheduleReminder or listScheduledTasks") long taskId) {
        boolean cancelled = SchedulerStore.cancelTask(jdbcTemplate, taskId);
        log.info("cancelTask id={} -> {}", taskId, cancelled ? "cancelled" : "not found");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", cancelled);
        result.put("taskId", taskId);
        result.put("message", cancelled ? "Task cancelled" : "Task not found or already finished");
        return result;
    }
}
