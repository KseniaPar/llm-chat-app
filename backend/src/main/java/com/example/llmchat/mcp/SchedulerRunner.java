package com.example.llmchat.mcp;

import com.example.mcp.scheduler.SchedulerStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Polls shared scheduler.db and executes due tasks. MCP tools only schedule; backend runs them.
 */
@Component
public class SchedulerRunner {

    private static final Logger log = LoggerFactory.getLogger(SchedulerRunner.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SchedulerRunner(
            @Value("${app.mcp.scheduler-db.absolute:data/scheduler.db}") String schedulerDbPath,
            ObjectMapper objectMapper) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + schedulerDbPath);
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${app.scheduler.poll-interval-ms:30000}")
    public void runDueTasks() {
        try {
            SchedulerStore.ensureSchema(jdbcTemplate);
            List<SchedulerStore.ScheduledTaskRow> due = SchedulerStore.findDueTasks(jdbcTemplate, Instant.now());
            for (SchedulerStore.ScheduledTaskRow task : due) {
                executeTask(task);
            }
        } catch (Exception exception) {
            log.warn("Scheduler poll failed: {}", exception.getMessage());
        }
    }

    private void executeTask(SchedulerStore.ScheduledTaskRow task) {
        try {
            if (SchedulerStore.TASK_REMINDER.equals(task.taskType())) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("type", SchedulerStore.TASK_REMINDER);
                result.put("message", task.message());
                result.put("executedAt", Instant.now().toString());
                SchedulerStore.recordResult(jdbcTemplate, task.id(), objectMapper.writeValueAsString(result));
                SchedulerStore.markCompleted(jdbcTemplate, task.id());
                log.info("Reminder executed taskId={} message='{}'", task.id(), task.message());
                return;
            }

            if (SchedulerStore.TASK_PERIODIC_SUMMARY.equals(task.taskType())) {
                Map<String, Object> summary = SchedulerStore.getSummary(jdbcTemplate, Instant.now().minusSeconds(3600));
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("type", SchedulerStore.TASK_PERIODIC_SUMMARY);
                result.put("label", task.message());
                result.put("executedAt", Instant.now().toString());
                result.put("summary", summary);
                SchedulerStore.recordResult(jdbcTemplate, task.id(), objectMapper.writeValueAsString(result));

                int interval = task.intervalMinutes() != null ? task.intervalMinutes() : 60;
                SchedulerStore.updateNextRun(
                        jdbcTemplate, task.id(), Instant.now().plusSeconds(interval * 60L));
                log.info("Periodic summary executed taskId={} next in {} min", task.id(), interval);
            }
        } catch (Exception exception) {
            log.warn("Failed to execute task {}: {}", task.id(), exception.getMessage());
        }
    }
}
