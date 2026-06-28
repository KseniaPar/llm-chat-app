package com.example.mcp.study;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
public class StudyDatabaseInitializer {

    private final JdbcTemplate jdbcTemplate;

    public StudyDatabaseInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        StudyTopicsSeed.ensureSeeded(jdbcTemplate);
    }
}
