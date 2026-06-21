package com.example.llmchat.task;

import java.util.regex.Pattern;

final class TaskStateTransitions {

    private static final Pattern READY_FOR_EXECUTION = Pattern.compile(
            "\\b(начн(?:ём|ем|и)|давай\\s+(?:начн|разбор|по\\s+плану)|план\\s+(?:подходит|ок|согласен)|"
                    + "готов\\s+(?:начать|к\\s+разбору)|соглас(?:ен|на|ны)|переходим\\s+к\\s+разбору|"
                    + "можем\\s+начинать|поехали)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern PLAN_AGREEMENT_CONFIRM = Pattern.compile(
            "\\b(да|ок|хорошо|ладно|угу|верно|давай|подходит|устраивает)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern READY_FOR_VALIDATION = Pattern.compile(
            "\\b(провер(?:ь|ить|ка)?\\s*(?:меня|мои\\s+знания)?|самопровер(?:ка|ить|им)?|"
                    + "задай\\s+(?:\\d+\\s+)?вопрос|протестируй|тест\\s+по|вопросы\\s+для\\s+проверки)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern MCQ_ANSWER = Pattern.compile(
            "^\\s*([A-Da-dА-Га-гДд])\\s*[).:]?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern VALIDATION_STEP = Pattern.compile(
            "вопрос\\s+(\\d+)\\s+из\\s+(\\d+)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern TOPIC_PROGRESS = Pattern.compile(
            "(\\d+)\\s*/\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private TaskStateTransitions() {
    }

    static boolean executionComplete(TaskState state) {
        if (state == null) {
            return false;
        }
        String step = lower(state.currentStep());
        String action = lower(state.expectedAction());
        if (step.contains("самопровер") || action.contains("самопровер") || action.contains("перейти к проверке")) {
            return true;
        }
        if (step.contains("заверш") || step.contains("последн") || step.contains("итог разбора")) {
            return true;
        }
        return topicProgressComplete(state.currentStep());
    }

    static boolean assistantSuggestsValidation(String assistantMessage) {
        if (assistantMessage == null || assistantMessage.isBlank()) {
            return false;
        }
        String lower = assistantMessage.toLowerCase();
        return lower.contains("самопровер")
                || lower.contains("проверим")
                || lower.contains("вопрос 1 из")
                || lower.contains("перейдём к вопрос");
    }

    static boolean validationReadyToFinish(TaskState state) {
        if (state == null) {
            return false;
        }
        String action = lower(state.expectedAction());
        if (action.contains("итог") || action.contains("подвести") || action.contains("заверш")) {
            return true;
        }
        int[] progress = parseValidationProgress(state.currentStep());
        return progress[0] >= progress[1];
    }

    static boolean assistantGivesSummary(String assistantMessage) {
        if (assistantMessage == null || assistantMessage.isBlank()) {
            return false;
        }
        String lower = assistantMessage.toLowerCase();
        return (lower.contains("итог") || lower.contains("в целом") || lower.contains("тема пройден"))
                && (lower.contains("молодец") || lower.contains("хорошо") || lower.contains("готов")
                || lower.contains("справил") || lower.contains("закончил"));
    }

    static boolean topicProgressComplete(String currentStep) {
        if (currentStep == null) {
            return false;
        }
        var matcher = TOPIC_PROGRESS.matcher(currentStep);
        if (matcher.find()) {
            return matcher.group(1).equals(matcher.group(2));
        }
        return false;
    }

    private static String lower(String text) {
        return text == null ? "" : text.toLowerCase();
    }

    static boolean readyForExecution(String userMessage, TaskState state) {
        if (userMessage == null) {
            return false;
        }
        String trimmed = userMessage.trim();
        if (READY_FOR_EXECUTION.matcher(trimmed).find()) {
            return true;
        }
        return state != null
                && state.phase() == TaskPhase.PLANNING
                && PlanningSteps.isAgreement(state.currentStep(), state.expectedAction())
                && trimmed.length() <= 40
                && PLAN_AGREEMENT_CONFIRM.matcher(trimmed).find();
    }

    static boolean readyForValidation(String userMessage) {
        return userMessage != null && READY_FOR_VALIDATION.matcher(userMessage.trim()).find();
    }

    static boolean isMcqAnswer(String userMessage) {
        return userMessage != null && MCQ_ANSWER.matcher(userMessage.trim()).matches();
    }

    static int[] parseValidationProgress(String currentStep) {
        if (currentStep != null) {
            var matcher = VALIDATION_STEP.matcher(currentStep);
            if (matcher.find()) {
                return new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
            }
        }
        return new int[] {1, 3};
    }
}
