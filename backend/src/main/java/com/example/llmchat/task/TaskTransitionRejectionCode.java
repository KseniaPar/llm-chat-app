package com.example.llmchat.task;

public enum TaskTransitionRejectionCode {
    SKIP_NOT_ALLOWED("Пропуск этапа не допускается"),
    PLAN_NOT_APPROVED("План не утверждён"),
    PAUSED_BLOCKS_PHASE("На паузе смена этапа запрещена"),
    VALIDATION_INCOMPLETE("Самопроверка не завершена"),
    ROLLBACK_NOT_ALLOWED("Возврат к предыдущему этапу запрещён"),
    NO_TASK_STATE("Задача не начата"),
    NOT_ON_PAUSE("Задача не на паузе"),
    ALREADY_PAUSED("Задача уже на паузе"),
    INVALID_TRANSITION("Переход недопустим для текущего состояния"),
    GUARD_FAILED("Условия перехода не выполнены");

    private final String defaultMessage;

    TaskTransitionRejectionCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
