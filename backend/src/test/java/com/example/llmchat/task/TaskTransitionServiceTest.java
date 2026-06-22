package com.example.llmchat.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskTransitionServiceTest {

    private static final String SESSION = "session-1";

    @Mock
    private TaskStateRepository taskStateRepository;

    @Mock
    private TaskTransitionRepository transitionRepository;

    private TaskTransitionGraph transitionGraph;
    private TaskTransitionService service;
    private Map<String, TaskState> states;

    @BeforeEach
    void setUp() {
        transitionGraph = new TaskTransitionGraph();
        service = new TaskTransitionService(taskStateRepository, transitionRepository, transitionGraph);
        states = new HashMap<>();
        when(taskStateRepository.findBySessionId(SESSION)).thenAnswer(invocation ->
                Optional.ofNullable(states.get(SESSION)));
        when(taskStateRepository.upsert(any(TaskState.class))).thenAnswer(invocation -> {
            TaskState state = invocation.getArgument(0);
            states.put(state.sessionId(), state);
            return state;
        });
    }

    @Test
    void rejectsExecutionWithoutPlanApproval() {
        states.put(SESSION, planningAgreement(false));

        TransitionResult result = service.apply(
                SESSION,
                TaskTransitionRequest.withContext(
                        TaskTransitionType.APPROVE_PLAN_TO_EXECUTION,
                        TaskTransitionTriggerSource.RULE,
                        service.buildExecutionState(states.get(SESSION)),
                        TaskTransitionContext.forRule("объясни буддизм", 0)));

        assertFalse(result.accepted());
        assertEquals(TaskTransitionRejectionCode.PLAN_NOT_APPROVED, result.rejectionCode());
        assertEquals(TaskPhase.PLANNING, states.get(SESSION).phase());
        verify(transitionRepository).insert(
                eq(SESSION),
                eq(TaskTransitionType.APPROVE_PLAN_TO_EXECUTION),
                eq(TaskPhase.PLANNING),
                eq(TaskPhase.EXECUTION),
                any(),
                any(),
                eq(TaskTransitionTriggerSource.RULE),
                eq(false),
                eq(TaskTransitionRejectionCode.PLAN_NOT_APPROVED),
                any());
    }

    @Test
    void rejectsPlanningToValidationSkipFromLlmProposal() {
        states.put(SESSION, planningAgreement(false));
        TaskState current = states.get(SESSION);
        TaskStateMachine.TaskStateProposal skip = new TaskStateMachine.TaskStateProposal(
                TaskPhase.VALIDATION,
                "Самопроверка",
                "Задать вопрос",
                current.taskTitle());

        TransitionResult result = service.applyFromProposal(
                SESSION,
                current,
                skip,
                TaskTransitionTriggerSource.LLM,
                "сразу тест",
                false);

        assertFalse(result.accepted());
        assertEquals(TaskTransitionRejectionCode.SKIP_NOT_ALLOWED, result.rejectionCode());
        assertEquals(TaskPhase.PLANNING, states.get(SESSION).phase());
    }

    @Test
    void auditsFinishFromPlanning() {
        states.put(SESSION, state(TaskPhase.PLANNING, PlanningSteps.AGREEMENT, "согласовать план", false));

        Optional<TransitionResult> result = service.auditUserSkipAttempt(SESSION, "Закончи задачу, всё понял");

        assertTrue(result.isPresent());
        assertFalse(result.get().accepted());
        assertEquals(TaskTransitionRejectionCode.SKIP_NOT_ALLOWED, result.get().rejectionCode());
        assertEquals(TaskPhase.PLANNING, states.get(SESSION).phase());
    }

    @Test
    void auditsFinishFromValidationWhenIncomplete() {
        states.put(SESSION, state(TaskPhase.VALIDATION, "Самопроверка: вопрос 1 из 3", "задать вопрос", false));

        Optional<TransitionResult> result = service.auditUserSkipAttempt(SESSION, "закончи без теста");

        assertTrue(result.isPresent());
        assertFalse(result.get().accepted());
        assertEquals(TaskTransitionRejectionCode.VALIDATION_INCOMPLETE, result.get().rejectionCode());
    }

    @Test
    void rejectsExecutionToDoneSkip() {
        states.put(SESSION, state(TaskPhase.EXECUTION, "тема 1/4", "объяснить", false));

        Optional<TransitionResult> result = service.auditUserSkipAttempt(SESSION, "закончи задачу, всё понял");

        assertTrue(result.isPresent());
        assertFalse(result.get().accepted());
        assertEquals(TaskTransitionRejectionCode.SKIP_NOT_ALLOWED, result.get().rejectionCode());
        assertEquals(TaskPhase.EXECUTION, states.get(SESSION).phase());
    }

    @Test
    void auditsDemoSkipFromPlanningToValidation() {
        states.put(SESSION, state(TaskPhase.PLANNING, PlanningSteps.CLARIFICATION, "уточнить цели", false));

        Optional<TransitionResult> result = service.auditUserSkipAttempt(
                SESSION,
                "Сразу задай тест A B C D, план не нужен");

        assertTrue(result.isPresent());
        assertFalse(result.get().accepted());
        assertEquals(TaskTransitionRejectionCode.SKIP_NOT_ALLOWED, result.get().rejectionCode());
        assertEquals(TaskPhase.PLANNING, states.get(SESSION).phase());
        verify(transitionRepository).insert(
                eq(SESSION),
                eq(TaskTransitionType.EXECUTION_TO_VALIDATION),
                eq(TaskPhase.PLANNING),
                eq(TaskPhase.VALIDATION),
                any(),
                any(),
                eq(TaskTransitionTriggerSource.RULE),
                eq(false),
                eq(TaskTransitionRejectionCode.SKIP_NOT_ALLOWED),
                any());
    }

    @Test
    void auditsSkipToValidationWithoutTaskState() {
        Optional<TransitionResult> result = service.auditUserSkipAttempt(
                SESSION,
                "Сразу задай тест A B C D, план не нужен");

        assertTrue(result.isPresent());
        assertFalse(result.get().accepted());
        assertEquals(TaskTransitionRejectionCode.SKIP_NOT_ALLOWED, result.get().rejectionCode());
        verify(transitionRepository).insert(
                eq(SESSION),
                eq(TaskTransitionType.EXECUTION_TO_VALIDATION),
                eq(TaskPhase.PLANNING),
                eq(TaskPhase.VALIDATION),
                eq(null),
                eq(null),
                eq(TaskTransitionTriggerSource.RULE),
                eq(false),
                eq(TaskTransitionRejectionCode.SKIP_NOT_ALLOWED),
                any());
    }

    @Test
    void happyPathRecordsAcceptedTransitions() {
        states.put(SESSION, planningAgreement(false));

        TransitionResult toExecution = service.apply(
                SESSION,
                TaskTransitionRequest.withContext(
                        TaskTransitionType.APPROVE_PLAN_TO_EXECUTION,
                        TaskTransitionTriggerSource.RULE,
                        service.buildExecutionState(states.get(SESSION)),
                        TaskTransitionContext.forRule("план ок, начинаем", 0)));
        assertTrue(toExecution.accepted());
        assertEquals(TaskPhase.EXECUTION, states.get(SESSION).phase());

        TransitionResult toValidation = service.apply(
                SESSION,
                TaskTransitionRequest.withContext(
                        TaskTransitionType.EXECUTION_TO_VALIDATION,
                        TaskTransitionTriggerSource.RULE,
                        service.buildValidationState(states.get(SESSION)),
                        TaskTransitionContext.forRule("проверь меня", 0)));
        assertTrue(toValidation.accepted());

        TaskState validationDone = new TaskState(
                SESSION,
                TaskPhase.VALIDATION,
                "Самопроверка: вопрос 3 из 3",
                "Подвести итог самопроверки",
                false,
                "Экзамен",
                Instant.now());
        states.put(SESSION, validationDone);

        TransitionResult toDone = service.apply(
                SESSION,
                TaskTransitionRequest.withContext(
                        TaskTransitionType.VALIDATION_TO_DONE,
                        TaskTransitionTriggerSource.AUTO,
                        service.buildDoneState(validationDone),
                        TaskTransitionContext.forAuto()));
        assertTrue(toDone.accepted());
        assertEquals(TaskPhase.DONE, states.get(SESSION).phase());

        ArgumentCaptor<Boolean> acceptedCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(transitionRepository, org.mockito.Mockito.atLeast(3)).insert(
                eq(SESSION),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                acceptedCaptor.capture(),
                any(),
                any());
        assertTrue(acceptedCaptor.getAllValues().stream().filter(Boolean::booleanValue).count() >= 3);
    }

    @Test
    void pauseBlocksPhaseChangeFromLlm() {
        states.put(SESSION, state(TaskPhase.EXECUTION, "тема 1/4", "объяснить", true));
        TaskState current = states.get(SESSION);
        TaskStateMachine.TaskStateProposal proposal = new TaskStateMachine.TaskStateProposal(
                TaskPhase.VALIDATION,
                "Самопроверка",
                "Задать вопрос",
                current.taskTitle());

        TransitionResult result = service.applyFromProposal(
                SESSION,
                current,
                proposal,
                TaskTransitionTriggerSource.LLM,
                "продолжаем",
                false);

        assertFalse(result.accepted());
        assertEquals(TaskTransitionRejectionCode.PAUSED_BLOCKS_PHASE, result.rejectionCode());
        assertEquals(TaskPhase.EXECUTION, states.get(SESSION).phase());
    }

    @Test
    void resumeAllowsNextValidTransition() {
        states.put(SESSION, state(TaskPhase.EXECUTION, "тема 4/4", "завершить разбор", true));

        TransitionResult resume = service.apply(
                SESSION,
                TaskTransitionRequest.of(
                        TaskTransitionType.RESUME,
                        TaskTransitionTriggerSource.USER_API,
                        service.buildResumedState(states.get(SESSION))));
        assertTrue(resume.accepted());
        assertFalse(states.get(SESSION).paused());

        TransitionResult toValidation = service.apply(
                SESSION,
                TaskTransitionRequest.withContext(
                        TaskTransitionType.EXECUTION_TO_VALIDATION,
                        TaskTransitionTriggerSource.RULE,
                        service.buildValidationState(states.get(SESSION)),
                        TaskTransitionContext.forRule("проверь меня", 0)));
        assertTrue(toValidation.accepted());
        assertEquals(TaskPhase.VALIDATION, states.get(SESSION).phase());
    }

    @Test
    void deferredApplyDoesNotPersistUntilExplicitUpsert() {
        states.put(SESSION, state(TaskPhase.PLANNING, PlanningSteps.CLARIFICATION, "уточнить цели", false));
        TaskState target = service.buildPlanningAgreementState(states.get(SESSION));

        TransitionResult result = service.apply(
                SESSION,
                TaskTransitionRequest.deferred(
                        TaskTransitionType.ADVANCE_PLANNING_SUBPHASE,
                        TaskTransitionTriggerSource.RULE,
                        target,
                        TaskTransitionContext.forRule("длинный ответ пользователя для плана", 3)));

        assertTrue(result.accepted());
        assertEquals(PlanningSteps.AGREEMENT, result.newState().currentStep());
        assertEquals(TaskPhase.PLANNING, states.get(SESSION).phase());
        verify(taskStateRepository, never()).upsert(any());
    }

    private static TaskState planningAgreement(boolean paused) {
        return state(
                TaskPhase.PLANNING,
                PlanningSteps.AGREEMENT,
                "Кратко предложить план и спросить подтверждение",
                paused);
    }

    private static TaskState state(TaskPhase phase, String step, String action, boolean paused) {
        return new TaskState(SESSION, phase, step, action, paused, "Экзамен", Instant.now());
    }
}
