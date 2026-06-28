package com.example.mcp.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SchedulerMcpStartupListener {

    private static final Logger log = LoggerFactory.getLogger(SchedulerMcpStartupListener.class);

    private final JdbcTemplate jdbcTemplate;

    public SchedulerMcpStartupListener(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM scheduled_tasks WHERE status = 'active'", Integer.class);
        log.info(
                "mcp-scheduler STDIO server ready — tools: scheduleReminder, schedulePeriodicSummary, "
                        + "listScheduledTasks, getSummary, cancelTask; active tasks: {}",
                count);
    }
}
