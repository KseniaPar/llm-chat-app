package com.example.llmchat.invariants;

import com.example.llmchat.dto.InvariantDto;
import com.example.llmchat.dto.InvariantsSnapshot;
import com.example.llmchat.personalization.UserProfile;
import com.example.llmchat.task.TaskPhase;
import com.example.llmchat.task.TaskState;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class InvariantsService {

    private final InvariantsRegistry registry;

    public InvariantsService(InvariantsRegistry registry) {
        this.registry = registry;
    }

    public List<InvariantDefinition> allDefinitions() {
        return registry.all();
    }

    public List<InvariantDto> allDtos() {
        return registry.all().stream().map(this::toDto).toList();
    }

    public List<InvariantDefinition> resolveActive(InvariantContext context) {
        List<InvariantDefinition> active = new ArrayList<>();
        Optional<TaskState> taskState = context.taskState();
        boolean taskActive = taskState.isPresent();
        TaskPhase phase = taskState.map(TaskState::phase).orElse(null);
        boolean hasConstraints = context.profile() != null
                && context.profile().constraints() != null
                && !context.profile().constraints().isBlank();

        for (InvariantDefinition rule : registry.all()) {
            if (isActive(rule, taskActive, phase, hasConstraints)) {
                active.add(rule);
            }
        }
        return active;
    }

    public String formatInvariantsBlock(InvariantContext context, InvariantCheckResult checkResult) {
        List<InvariantDefinition> active = resolveActive(context);
        if (active.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder("""
                Бизнес-инварианты учебного процесса (приоритет над общими правилами краткости):
                Эти правила нельзя нарушать и нельзя предлагать обход.
                При конфликте с запросом студента — откажи, сославшись на id (например INV-BIZ-01).
                """);
        for (InvariantDefinition rule : active) {
            builder.append("\n- ").append(rule.id()).append(" «").append(rule.title()).append("»: ");
            if (rule.description() != null && !rule.description().isBlank()) {
                builder.append(rule.description().trim());
            }
        }
        if (context.profile() != null
                && context.profile().constraints() != null
                && !context.profile().constraints().isBlank()) {
            builder.append("\n\nОграничения профиля студента (INV-BIZ-13): ")
                    .append(context.profile().constraints().trim());
        }
        if (checkResult != null && !checkResult.softHints().isEmpty()) {
            builder.append("\n\nВнимание:");
            for (String hint : checkResult.softHints()) {
                builder.append("\n- ").append(hint);
            }
        }
        return builder.toString().trim();
    }

    public List<String> buildInvariantsLogs(
            InvariantContext context,
            boolean appliedToPrompt,
            InvariantCheckResult checkResult) {
        List<String> logs = new ArrayList<>();
        List<InvariantDefinition> active = resolveActive(context);
        if (!appliedToPrompt) {
            logs.add("INVARIANTS: блок не добавлен");
            return logs;
        }
        logs.add("INVARIANTS: " + active.size() + " правил добавлено в промпт");
        if (checkResult != null && checkResult.hardBlock()) {
            for (InvariantDefinition rule : checkResult.hardBlocked()) {
                logs.add("INVARIANTS → отказ: " + rule.id());
            }
        } else if (checkResult != null && !checkResult.matched().isEmpty()) {
            for (InvariantDefinition rule : checkResult.matched()) {
                if (!rule.hardBlock()) {
                    logs.add("INVARIANTS → возможный конфликт: " + rule.id());
                }
            }
        }
        return logs;
    }

    public InvariantsSnapshot toSnapshot(
            InvariantContext context,
            boolean appliedToPrompt,
            InvariantCheckResult checkResult) {
        List<InvariantDefinition> active = resolveActive(context);
        List<InvariantDto> dtos = active.stream().map(this::toDto).toList();
        List<String> violatedIds = checkResult != null
                ? checkResult.matched().stream().map(InvariantDefinition::id).toList()
                : List.of();
        return new InvariantsSnapshot(dtos.size(), dtos, appliedToPrompt, List.copyOf(violatedIds));
    }

    private boolean isActive(
            InvariantDefinition rule,
            boolean taskActive,
            TaskPhase phase,
            boolean hasConstraints) {
        return switch (rule.activeWhen()) {
            case ALWAYS -> true;
            case TASK_ACTIVE -> taskActive;
            case TASK_PLANNING -> taskActive && phase == TaskPhase.PLANNING;
            case TASK_EXECUTION -> taskActive && phase == TaskPhase.EXECUTION;
            case TASK_VALIDATION -> taskActive && phase == TaskPhase.VALIDATION;
            case TASK_DONE -> taskActive && phase == TaskPhase.DONE;
            case PROFILE_CONSTRAINTS -> hasConstraints;
        };
    }

    private InvariantDto toDto(InvariantDefinition rule) {
        return new InvariantDto(rule.id(), rule.title(), rule.description(), rule.hardBlock());
    }
}
