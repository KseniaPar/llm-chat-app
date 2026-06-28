package com.example.llmchat.controller;

import com.example.mcp.scheduler.SchedulerStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mcp/scheduler")
public class SchedulerController {

    private static final Logger log = LoggerFactory.getLogger(SchedulerController.class);

    private final JdbcTemplate jdbcTemplate;

    public SchedulerController(
            @Value("${app.mcp.scheduler-db.absolute:data/scheduler.db}") String schedulerDbPath) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + schedulerDbPath);
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @GetMapping("/tasks")
    public Map<String, Object> listTasks() {
        log.debug("GET /api/mcp/scheduler/tasks");
        SchedulerStore.ensureSchema(jdbcTemplate);
        List<Map<String, Object>> tasks = SchedulerStore.listActiveTasks(jdbcTemplate);
        return Map.of("count", tasks.size(), "tasks", tasks);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam(required = false) String since) {
        log.debug("GET /api/mcp/scheduler/summary since={}", since);
        SchedulerStore.ensureSchema(jdbcTemplate);
        Instant sinceInstant = since != null && !since.isBlank() ? Instant.parse(since) : null;
        return SchedulerStore.getSummary(jdbcTemplate, sinceInstant);
    }

    @PostMapping("/demo-reset")
    public Map<String, Object> demoReset() {
        log.info("POST /api/mcp/scheduler/demo-reset");
        SchedulerStore.ensureSchema(jdbcTemplate);
        int cancelled = SchedulerStore.cancelAllActiveTasks(jdbcTemplate);
        int deleted = SchedulerStore.deleteAllResults(jdbcTemplate);
        return Map.of("cancelledTasks", cancelled, "deletedResults", deleted);
    }
}
