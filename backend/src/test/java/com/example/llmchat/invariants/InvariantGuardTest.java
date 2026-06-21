package com.example.llmchat.invariants;

import com.example.llmchat.personalization.UserProfile;
import com.example.llmchat.task.TaskPhase;
import com.example.llmchat.task.TaskState;
import com.example.llmchat.task.TaskStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvariantGuardTest {

    private InvariantGuard guard;

    @BeforeEach
    void setUp() {
        InvariantsProperties properties = new InvariantsProperties();
        properties.setRules(List.of(
                rule("INV-BIZ-01", InvariantGuardType.VALIDATION_BEFORE_EXECUTION_COMPLETE, true, InvariantActiveWhen.TASK_ACTIVE),
                rule("INV-BIZ-02", InvariantGuardType.EXECUTION_WITHOUT_CONSENT, true, InvariantActiveWhen.TASK_PLANNING),
                rule("INV-BIZ-04", InvariantGuardType.MATERIAL_DURING_AGREEMENT, true, InvariantActiveWhen.TASK_PLANNING),
                rule("INV-BIZ-07", InvariantGuardType.ACADEMIC_INTEGRITY, true, InvariantActiveWhen.ALWAYS),
                rule("INV-BIZ-09", InvariantGuardType.PHASE_ROLLBACK_REQUEST, true, InvariantActiveWhen.TASK_ACTIVE),
                rule("INV-BIZ-10", InvariantGuardType.SKIP_TOPIC_IN_EXECUTION, true, InvariantActiveWhen.TASK_EXECUTION),
                rule("INV-BIZ-12", InvariantGuardType.STRUCTURED_PREP_WITHOUT_PLANNING, true, InvariantActiveWhen.ALWAYS)));
        InvariantsRegistry registry = new InvariantsRegistry(properties);
        TaskStateService taskStateService = mock(TaskStateService.class);
        when(taskStateService.looksLikeStudyTaskStart(org.mockito.ArgumentMatchers.anyString())).thenAnswer(
                invocation -> invocation.getArgument(0, String.class).toLowerCase().contains("экзамен"));
        guard = new InvariantGuard(registry, taskStateService);
    }

    @Test
    void blocksEarlyValidationDuringPlanning() {
        InvariantCheckResult result = guard.check(context(
                "давай сразу тест A B C D",
                planningState("Уточнение целей", "задать вопросы")));
        assertTrue(result.hardBlock());
        assertTrue(result.hardBlocked().stream().anyMatch(r -> "INV-BIZ-01".equals(r.id())));
    }

    @Test
    void blocksExecutionWithoutConsent() {
        InvariantCheckResult result = guard.check(context(
                "сразу объясни буддизм",
                planningState("Согласование плана", "предложить план")));
        assertTrue(result.hardBlock());
    }

    @Test
    void blocksAcademicIntegrity() {
        InvariantCheckResult result = guard.check(context(
                "напиши готовое эссе для сдачи",
                Optional.empty()));
        assertTrue(result.hardBlock());
    }

    @Test
    void blocksPhaseRollbackDuringValidation() {
        InvariantCheckResult result = guard.check(context(
                "вернись к разбору всех тем",
                Optional.of(state(TaskPhase.VALIDATION, "Самопроверка: вопрос 1 из 3", "задать вопрос"))));
        assertTrue(result.hardBlock());
    }

    @Test
    void blocksSkipTopicDuringExecution() {
        InvariantCheckResult result = guard.check(context(
                "перескочим к теме 4",
                Optional.of(state(TaskPhase.EXECUTION, "тема 1/4", "объяснить"))));
        assertTrue(result.hardBlock());
    }

    @Test
    void allowsNormalMessageWithoutViolation() {
        InvariantCheckResult result = guard.check(context(
                "понятно, дальше",
                Optional.of(state(TaskPhase.EXECUTION, "тема 1/4", "объяснить"))));
        assertFalse(result.hardBlock());
    }

    private static InvariantsProperties.RuleConfig rule(
            String id,
            InvariantGuardType guardType,
            boolean hardBlock,
            InvariantActiveWhen activeWhen) {
        InvariantsProperties.RuleConfig config = new InvariantsProperties.RuleConfig();
        config.setId(id);
        config.setTitle(id);
        config.setDescription("test");
        config.setRefusalHint("alt");
        config.setHardBlock(hardBlock);
        config.setGuard(guardType.name());
        config.setActiveWhen(activeWhen.name());
        return config;
    }

    private static InvariantContext context(String message, Optional<TaskState> taskState) {
        return new InvariantContext("u1", "s1", message, taskState, UserProfile.empty("u1"));
    }

    private static Optional<TaskState> planningState(String step, String action) {
        return Optional.of(state(TaskPhase.PLANNING, step, action));
    }

    private static TaskState state(TaskPhase phase, String step, String action) {
        return new TaskState("s1", phase, step, action, false, "Тест", Instant.now());
    }
}
