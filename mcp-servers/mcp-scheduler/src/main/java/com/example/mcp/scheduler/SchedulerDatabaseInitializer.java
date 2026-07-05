package com.example.mcp.scheduler;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
public class SchedulerDatabaseInitializer {

    private final JdbcTemplate jdbcTemplate;

    public SchedulerDatabaseInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        SchedulerStore.ensureSchema(jdbcTemplate);
    }
}
