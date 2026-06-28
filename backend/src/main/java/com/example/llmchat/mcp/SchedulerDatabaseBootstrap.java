package com.example.llmchat.mcp;

import com.example.mcp.scheduler.SchedulerStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Ensures the shared scheduler.db schema exists before MCP scheduler server and SchedulerRunner connect.
 */
@Component
public class SchedulerDatabaseBootstrap {

    private static final Logger log = LoggerFactory.getLogger(SchedulerDatabaseBootstrap.class);

    private final String schedulerDbPath;

    public SchedulerDatabaseBootstrap(
            @Value("${app.mcp.scheduler-db.absolute:data/scheduler.db}") String schedulerDbPath) {
        this.schedulerDbPath = schedulerDbPath;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureSchedulerDatabase() {
        try {
            Path path = Paths.get(schedulerDbPath).toAbsolutePath().normalize();
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }

            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("org.sqlite.JDBC");
            dataSource.setUrl("jdbc:sqlite:" + path);

            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            SchedulerStore.ensureSchema(jdbcTemplate);

            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM scheduled_tasks WHERE status = 'active'", Integer.class);
            log.info("Scheduler DB ready at {} — {} active task(s)", path, count);
        } catch (Exception exception) {
            log.warn("Failed to bootstrap scheduler DB: {}", exception.getMessage());
        }
    }
}
