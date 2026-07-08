package com.example.llmchat.rag;

import com.example.llmchat.dto.RagChunkMetaDto;
import com.example.llmchat.dto.RagDemoResponse;
import com.example.llmchat.dto.RagStrategyDemoDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

@Service
public class RagDemoService {

    private final RagCorpusLoader corpusLoader;
    private final RagIndexStore indexStore;
    private final String indexDbPath;
    private final String embeddingModel;
    private final int fixedChunkSize;
    private final int chunkOverlap;
    private final int embeddingBatchSize;

    public RagDemoService(
            RagCorpusLoader corpusLoader,
            RagIndexStore indexStore,
            @Value("${app.rag.index-db:data/rag-index.db}") String indexDbPath,
            @Value("${app.rag.cloud.embedding-model:openai/text-embedding-3-small}") String embeddingModel,
            @Value("${app.rag.fixed-chunk-size:1200}") int fixedChunkSize,
            @Value("${app.rag.chunk-overlap:200}") int chunkOverlap,
            @Value("${app.rag.embedding-batch-size:64}") int embeddingBatchSize) {
        this.corpusLoader = corpusLoader;
        this.indexStore = indexStore;
        this.indexDbPath = Paths.get(indexDbPath).toAbsolutePath().normalize().toString();
        this.embeddingModel = embeddingModel;
        this.fixedChunkSize = fixedChunkSize;
        this.chunkOverlap = chunkOverlap;
        this.embeddingBatchSize = embeddingBatchSize;
    }

    public RagDemoResponse buildDemo() {
        RagCorpusLoader.CorpusStats corpus = corpusLoader.stats();
        List<RagDocument> docs = corpusLoader.loadAll();
        String corpusFile = docs.isEmpty() ? "—" : docs.get(0).title();

        return new RagDemoResponse(
                "День 25 — мини-чат с RAG и памятью задачи",
                corpusFile,
                corpus.documentCount(),
                corpus.estimatedPages(),
                corpus.totalChars(),
                indexDbPath,
                List.of(
                        "1. Загрузка корпуса (PDF → текст, Apache PDFBox)",
                        "2. Chunking — FIXED_SIZE или STRUCTURE",
                        "3. Embeddings — OpenRouter / " + embeddingModel,
                        "4. Сохранение в SQLite — chunks + metadata + embedding BLOB",
                        "5. Query rewrite → vector search (pool) → similarity filter → top-K",
                        "6. Обязательные sources + quotes в ответе, режим «не знаю» при слабом контексте",
                        "7. Мультитurn чат: история + RAG каждый ход + task memory (цель, уточнения, термины)",
                        "8. Сценарии диалогов: 2 × 10–15 сообщений по корпусу «Основы православия»"),
                List.of(
                        "Корпус: PDF «" + corpusFile + "» (~" + corpus.estimatedPages() + " стр.)",
                        "Извлечение текста: Apache PDFBox 3",
                        "Chunking FIXED: " + fixedChunkSize + " симв., overlap " + chunkOverlap,
                        "Chunking STRUCTURE: главы / разделы / нумерация",
                        "Embeddings: " + embeddingModel + " (батчи по " + embeddingBatchSize + ")",
                        "Хранение: SQLite → " + indexDbPath,
                        "Метаданные чанка: chunk_id, source, title, section, char_start/end, token_count"),
                buildStrategyDemo(ChunkingStrategy.FIXED_SIZE),
                buildStrategyDemo(ChunkingStrategy.STRUCTURE));
    }

    private RagStrategyDemoDto buildStrategyDemo(ChunkingStrategy strategy) {
        var indexRepository = indexStore.cloud();
        Optional<RagIndexRepository.StrategyStats> stats = indexRepository.statsFor(strategy);
        List<RagIndexRepository.ChunkMetaRow> samples = indexRepository.sampleChunkMetaSpread(strategy);
        int dimensions = indexRepository.embeddingDimensions(strategy).orElse(0);
        int chunkCount = indexRepository.countChunks(strategy);

        if (stats.isEmpty()) {
            return new RagStrategyDemoDto(
                    strategy,
                    label(strategy),
                    description(strategy),
                    chunkCount,
                    0,
                    0,
                    0,
                    null,
                    dimensions,
                    samples.stream().map(this::toDto).toList());
        }

        RagIndexRepository.StrategyStats s = stats.get();
        return new RagStrategyDemoDto(
                strategy,
                label(strategy),
                description(strategy),
                Math.max(chunkCount, s.chunkCount()),
                s.avgChunkSize(),
                s.minChunkSize(),
                s.maxChunkSize(),
                s.indexedAt(),
                dimensions,
                samples.stream().map(this::toDto).toList());
    }

    private RagChunkMetaDto toDto(RagIndexRepository.ChunkMetaRow row) {
        return new RagChunkMetaDto(
                row.position(),
                row.chunkIndex(),
                row.totalChunks(),
                row.chunkId(),
                row.source(),
                row.title(),
                row.section(),
                row.charStart(),
                row.charEnd(),
                row.tokenCount(),
                row.preview());
    }

    private static String label(ChunkingStrategy strategy) {
        return switch (strategy) {
            case FIXED_SIZE -> "FIXED_SIZE — фиксированный размер";
            case STRUCTURE -> "STRUCTURE — по структуре документа";
        };
    }

    private static String description(ChunkingStrategy strategy) {
        return switch (strategy) {
            case FIXED_SIZE -> "Равные окна ~1200 символов с overlap 200, разрыв по абзацам";
            case STRUCTURE -> "Разбиение по заголовкам (Глава, Часть, Раздел) с fallback на окна";
        };
    }
}
