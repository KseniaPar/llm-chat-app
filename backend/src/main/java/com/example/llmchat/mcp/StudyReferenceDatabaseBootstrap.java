package com.example.llmchat.mcp;

import com.example.mcp.study.StudyTopicsSeed;
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
 * Ensures the shared study-reference.db is populated before MCP study server connects.
 */
@Component
public class StudyReferenceDatabaseBootstrap {

    private static final Logger log = LoggerFactory.getLogger(StudyReferenceDatabaseBootstrap.class);

    private final String studyDbPath;

    public StudyReferenceDatabaseBootstrap(
            @Value("${app.mcp.study-db.absolute:data/study-reference.db}") String studyDbPath) {
        this.studyDbPath = studyDbPath;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureStudyDatabase() {
        try {
            Path path = Paths.get(studyDbPath).toAbsolutePath().normalize();
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }

            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("org.sqlite.JDBC");
            dataSource.setUrl("jdbc:sqlite:" + path);

            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            StudyTopicsSeed.ensureSeeded(jdbcTemplate);
            StudyTopicsSeed.ensureSupplementalTopics(jdbcTemplate);
            StudyTopicsSeed.patchTopicCorrections(jdbcTemplate);

            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM study_topics", Integer.class);
            log.info("Study reference DB ready at {} — {} topics", path, count);
        } catch (Exception exception) {
            log.warn("Failed to bootstrap study reference DB: {}", exception.getMessage());
        }
    }
}
