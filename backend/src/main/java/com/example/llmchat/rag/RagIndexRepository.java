package com.example.llmchat.rag;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class RagIndexRepository {

    private final JdbcTemplate jdbc;
    private final String dbPath;

    public RagIndexRepository(String indexDbPath) {
        this.dbPath = Paths.get(indexDbPath).toAbsolutePath().normalize().toString();
        RagDatabaseBootstrap.ensureSchema(indexDbPath);
        this.jdbc = RagDatabaseBootstrap.createJdbcTemplate(indexDbPath);
    }

    public String dbPath() {
        return dbPath;
    }

    public void clearStrategy(ChunkingStrategy strategy) {
        jdbc.update("DELETE FROM rag_chunks WHERE strategy = ?", strategy.name());
        jdbc.update("""
                DELETE FROM rag_documents
                WHERE id NOT IN (SELECT DISTINCT document_id FROM rag_chunks)
                """);
    }

    public long upsertDocument(RagDocument document) {
        jdbc.update("""
                INSERT INTO rag_documents (source_path, title, doc_type, indexed_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(source_path) DO UPDATE SET
                    title = excluded.title,
                    doc_type = excluded.doc_type,
                    indexed_at = excluded.indexed_at
                """,
                document.sourcePath(),
                document.title(),
                document.docType(),
                Instant.now().toString());
        Long id = jdbc.queryForObject(
                "SELECT id FROM rag_documents WHERE source_path = ?",
                Long.class,
                document.sourcePath());
        return id != null ? id : 0L;
    }

    public void insertChunk(long documentId, ChunkingStrategy strategy, RagChunk chunk, float[] embedding) {
        jdbc.update("""
                INSERT INTO rag_chunks
                (document_id, chunk_id, strategy, content, section, char_start, char_end, embedding, token_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                documentId,
                chunk.chunkId(),
                strategy.name(),
                chunk.content(),
                chunk.section(),
                chunk.charStart(),
                chunk.charEnd(),
                EmbeddingService.serialize(embedding),
                chunk.tokenCount());
    }

    public void recordIndexRun(ChunkingStrategy strategy, int count, double avg, int min, int max) {
        jdbc.update("""
                INSERT INTO rag_index_runs (strategy, chunk_count, avg_chunk_size, min_chunk_size, max_chunk_size, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                strategy.name(), count, avg, min, max, Instant.now().toString());
    }

    public List<IndexedChunk> loadChunks(ChunkingStrategy strategy) {
        return jdbc.query("""
                SELECT c.chunk_id, c.content, c.section, c.embedding, c.token_count,
                       d.source_path, d.title
                FROM rag_chunks c
                JOIN rag_documents d ON d.id = c.document_id
                WHERE c.strategy = ?
                """,
                (rs, rowNum) -> new IndexedChunk(
                        rs.getString("chunk_id"),
                        rs.getString("source_path"),
                        rs.getString("title"),
                        rs.getString("section"),
                        rs.getString("content"),
                        rs.getInt("token_count"),
                        EmbeddingService.deserialize(rs.getBytes("embedding"))),
                strategy.name());
    }

    public Optional<StrategyStats> statsFor(ChunkingStrategy strategy) {
        List<StrategyStats> rows = jdbc.query("""
                SELECT strategy, chunk_count, avg_chunk_size, min_chunk_size, max_chunk_size, created_at
                FROM rag_index_runs
                WHERE strategy = ?
                ORDER BY id DESC
                LIMIT 1
                """,
                (rs, rowNum) -> new StrategyStats(
                        ChunkingStrategy.valueOf(rs.getString("strategy")),
                        rs.getInt("chunk_count"),
                        rs.getDouble("avg_chunk_size"),
                        rs.getInt("min_chunk_size"),
                        rs.getInt("max_chunk_size"),
                        rs.getString("created_at")),
                strategy.name());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<StrategyStats> allStats() {
        List<StrategyStats> result = new ArrayList<>();
        for (ChunkingStrategy strategy : ChunkingStrategy.values()) {
            statsFor(strategy).ifPresent(result::add);
        }
        return result;
    }

    public int countChunks(ChunkingStrategy strategy) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_chunks WHERE strategy = ?",
                Integer.class,
                strategy.name());
        return count != null ? count : 0;
    }

    public List<ChunkMetaRow> sampleChunkMetaSpread(ChunkingStrategy strategy) {
        int total = countChunks(strategy);
        if (total == 0) {
            return List.of();
        }
        List<ChunkMetaRow> samples = new ArrayList<>();
        addSampleAtOffset(strategy, total, 0, "начало", samples);

        int[] middleOffsets = middleThreeOffsets(total);
        for (int i = 0; i < middleOffsets.length; i++) {
            String label = middleOffsets.length == 1 ? "середина" : "середина " + (i + 1);
            addSampleAtOffset(strategy, total, middleOffsets[i], label, samples);
        }

        if (total > 1) {
            addSampleAtOffset(strategy, total, total - 1, "конец", samples);
        }
        return samples;
    }

    private static int[] middleThreeOffsets(int total) {
        if (total <= 1) {
            return new int[] {0};
        }
        if (total == 2) {
            return new int[] {0, 1};
        }
        if (total == 3) {
            return new int[] {1};
        }
        if (total == 4) {
            return new int[] {1, 2};
        }
        int center = total / 2;
        return new int[] {
                Math.max(1, center - 1),
                center,
                Math.min(total - 2, center + 1)
        };
    }

    private void addSampleAtOffset(
            ChunkingStrategy strategy,
            int total,
            int offset,
            String position,
            List<ChunkMetaRow> samples) {
        List<ChunkMetaRow> rows = jdbc.query("""
                SELECT c.chunk_id, c.section, c.char_start, c.char_end, c.token_count,
                       COALESCE(d.source_path, 'unknown') AS source_path,
                       COALESCE(d.title, c.chunk_id) AS title,
                       substr(c.content, 1, 220) AS preview
                FROM rag_chunks c
                LEFT JOIN rag_documents d ON d.id = c.document_id
                WHERE c.strategy = ?
                ORDER BY c.id
                LIMIT 1 OFFSET ?
                """,
                (rs, rowNum) -> new ChunkMetaRow(
                        position,
                        offset + 1,
                        total,
                        rs.getString("chunk_id"),
                        rs.getString("source_path"),
                        rs.getString("title"),
                        rs.getString("section"),
                        rs.getInt("char_start"),
                        rs.getInt("char_end"),
                        rs.getInt("token_count"),
                        rs.getString("preview")),
                strategy.name(),
                offset);
        samples.addAll(rows);
    }

    public Optional<Integer> embeddingDimensions(ChunkingStrategy strategy) {
        List<Integer> rows = jdbc.query("""
                SELECT length(c.embedding) / 4 AS dims
                FROM rag_chunks c
                WHERE c.strategy = ?
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getInt("dims"),
                strategy.name());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public record ChunkMetaRow(
            String position,
            int chunkIndex,
            int totalChunks,
            String chunkId,
            String source,
            String title,
            String section,
            int charStart,
            int charEnd,
            int tokenCount,
            String preview) {
    }

    public record IndexedChunk(
            String chunkId,
            String source,
            String title,
            String section,
            String content,
            int tokenCount,
            float[] embedding) {
    }

    public record StrategyStats(
            ChunkingStrategy strategy,
            int chunkCount,
            double avgChunkSize,
            int minChunkSize,
            int maxChunkSize,
            String indexedAt) {
    }
}
