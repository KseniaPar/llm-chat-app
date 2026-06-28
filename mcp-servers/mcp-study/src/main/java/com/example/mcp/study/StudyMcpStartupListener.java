package com.example.mcp.study;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class StudyMcpStartupListener {

    private static final Logger log = LoggerFactory.getLogger(StudyMcpStartupListener.class);

    private final JdbcTemplate jdbcTemplate;

    public StudyMcpStartupListener(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM study_topics", Integer.class);
        log.info("mcp-study STDIO server ready — tools: searchTopic, getExamOutline; topics in DB: {}", count);
    }
}
