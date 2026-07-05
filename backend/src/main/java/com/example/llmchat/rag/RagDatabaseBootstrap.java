package com.example.llmchat.rag;

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

@Component
public class RagDatabaseBootstrap {

    private static final Logger log = LoggerFactory.getLogger(RagDatabaseBootstrap.class);

    private final String indexDbPath;

    public RagDatabaseBootstrap(@Value("${app.rag.index-db:data/rag-index.db}") String indexDbPath) {
        this.indexDbPath = indexDbPath;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureSchema() {
        try {
            Path path = Paths.get(indexDbPath).toAbsolutePath().normalize();
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            JdbcTemplate jdbc = createJdbc(path);
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS rag_documents (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        source_path TEXT NOT NULL UNIQUE,
                        title TEXT NOT NULL,
                        doc_type TEXT NOT NULL,
                        indexed_at TEXT NOT NULL
                    )
                    """);
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS rag_chunks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        document_id INTEGER NOT NULL,
                        chunk_id TEXT NOT NULL,
                        strategy TEXT NOT NULL,
                        content TEXT NOT NULL,
                        section TEXT,
                        char_start INTEGER NOT NULL,
                        char_end INTEGER NOT NULL,
                        embedding BLOB NOT NULL,
                        token_count INTEGER NOT NULL,
                        FOREIGN KEY (document_id) REFERENCES rag_documents(id)
                    )
                    """);
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS rag_index_runs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        strategy TEXT NOT NULL,
                        chunk_count INTEGER NOT NULL,
                        avg_chunk_size REAL NOT NULL,
                        min_chunk_size INTEGER NOT NULL,
                        max_chunk_size INTEGER NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_rag_chunks_strategy ON rag_chunks(strategy)");
            log.info("RAG index DB ready at {}", path);
        } catch (Exception exception) {
            log.warn("Failed to bootstrap RAG DB: {}", exception.getMessage());
        }
    }

    public JdbcTemplate createJdbcTemplate() {
        Path path = Paths.get(indexDbPath).toAbsolutePath().normalize();
        return createJdbc(path);
    }

    private JdbcTemplate createJdbc(Path path) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + path);
        return new JdbcTemplate(dataSource);
    }
}
