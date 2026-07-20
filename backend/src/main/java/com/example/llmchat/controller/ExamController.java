package com.example.llmchat.controller;

import com.example.llmchat.dto.ExamChatRequest;
import com.example.llmchat.dto.ExamChatResponse;
import com.example.llmchat.dto.ExamConspectRequest;
import com.example.llmchat.dto.ExamConspectResponse;
import com.example.llmchat.dto.ExamJobDto;
import com.example.llmchat.dto.ExamStatusResponse;
import com.example.llmchat.exam.ExamAssistantService;
import com.example.llmchat.exam.ExamConspectService;
import com.example.llmchat.exam.ExamJob;
import com.example.llmchat.exam.ExamJobStatus;
import com.example.llmchat.exam.ExamJobStore;
import com.example.llmchat.exam.ExamPipelineService;
import com.example.llmchat.platform.PlatformServerService;
import com.example.llmchat.rag.ChunkingStrategy;
import com.example.llmchat.rag.RagCompletionService;
import com.example.llmchat.rag.RagIndexStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/exam")
public class ExamController {

    private static final Logger log = LoggerFactory.getLogger(ExamController.class);

    private final ExamPipelineService pipelineService;
    private final ExamAssistantService assistantService;
    private final ExamConspectService conspectService;
    private final ExamJobStore jobStore;
    private final PlatformServerService platformServerService;
    private final RagCompletionService ragCompletionService;
    private final RagIndexStore ragIndexStore;
    private final String transcriptionModel;
    private final String audioDir;
    private final String notesDir;

    public ExamController(
            ExamPipelineService pipelineService,
            ExamAssistantService assistantService,
            ExamConspectService conspectService,
            ExamJobStore jobStore,
            PlatformServerService platformServerService,
            RagCompletionService ragCompletionService,
            RagIndexStore ragIndexStore,
            @Value("${app.exam.transcription-model}") String transcriptionModel,
            @Value("${app.exam.audio-dir:data/exam-audio}") String audioDir,
            @Value("${app.exam.notes-dir:data/exam-notes}") String notesDir) {
        this.pipelineService = pipelineService;
        this.assistantService = assistantService;
        this.conspectService = conspectService;
        this.jobStore = jobStore;
        this.platformServerService = platformServerService;
        this.ragCompletionService = ragCompletionService;
        this.ragIndexStore = ragIndexStore;
        this.transcriptionModel = transcriptionModel;
        this.audioDir = audioDir;
        this.notesDir = notesDir;
    }

    @GetMapping("/status")
    public ExamStatusResponse status() {
        List<ExamJob> jobs = jobStore.list();
        int ready = (int) jobs.stream().filter(j -> j.status() == ExamJobStatus.READY).count();
        int chunks = ragIndexStore.exam().countChunks(ChunkingStrategy.STRUCTURE);
        return new ExamStatusResponse(
                platformServerService.info().version(),
                assistantService.cloudConfigured(),
                transcriptionModel,
                ragCompletionService.cloudModel(),
                jobs.size(),
                ready,
                chunks,
                ragIndexStore.examPath(),
                audioDir,
                notesDir);
    }

    @GetMapping("/jobs")
    public List<ExamJobDto> listJobs() {
        return jobStore.list().stream().map(this::toDto).toList();
    }

    @GetMapping("/jobs/{jobId}")
    public ExamJobDto getJob(@PathVariable String jobId) {
        return jobStore.find(jobId)
                .map(this::toDto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "job not found"));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExamJobDto upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "subject", required = false) String subject) {
        log.info("POST /api/exam/upload file={} size={}", file.getOriginalFilename(), file.getSize());
        try {
            ExamJob job = pipelineService.enqueueUpload(file, title, subject);
            return toDto(job);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
        }
    }

    @PostMapping("/jobs/{jobId}/retry")
    public ExamJobDto retryJob(@PathVariable String jobId) {
        log.info("POST /api/exam/jobs/{}/retry", jobId);
        try {
            return toDto(pipelineService.retryJob(jobId));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
    }

    @PostMapping("/chat")
    public ExamChatResponse chat(@RequestBody ExamChatRequest request) {
        String question = request != null ? request.question() : null;
        if (question == null || question.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question is required");
        }
        log.info("POST /api/exam/chat qLen={}", question.trim().length());
        try {
            ExamAssistantService.ExamAnswer answer =
                    assistantService.answer(question.trim());
            return new ExamChatResponse(
                    question.trim(),
                    answer.answer(),
                    answer.model(),
                    answer.durationMs(),
                    answer.trustCited(),
                    answer.sources(),
                    answer.citations(),
                    answer.toolCalls());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    @GetMapping("/jobs/{jobId}/conspect")
    public ExamConspectResponse getConspect(@PathVariable String jobId) {
        try {
            ExamConspectService.ExamConspectResult result = conspectService.read(jobId.trim());
            return new ExamConspectResponse(result.jobId(), result.path(), result.markdown());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
        }
    }

    @PostMapping("/conspect")
    public ExamConspectResponse conspect(@RequestBody ExamConspectRequest request) {
        String jobId = request != null ? request.jobId() : null;
        if (jobId == null || jobId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "jobId is required");
        }
        try {
            ExamConspectService.ExamConspectResult result = conspectService.generate(jobId.trim());
            return new ExamConspectResponse(result.jobId(), result.path(), result.markdown());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
        }
    }

    private ExamJobDto toDto(ExamJob job) {
        List<com.example.llmchat.exam.ExamTranscriptSegment> segments =
                job.status() == ExamJobStatus.READY || job.status() == ExamJobStatus.FAILED
                        ? job.segments()
                        : List.of();
        return new ExamJobDto(
                job.id(),
                job.title(),
                job.subject(),
                job.originalFilename(),
                job.status(),
                job.message(),
                job.language(),
                job.durationSec(),
                job.segments() != null ? job.segments().size() : 0,
                segments,
                job.transcriptPath(),
                job.cleanPath(),
                job.notesPath(),
                job.createdAt(),
                job.updatedAt());
    }
}
