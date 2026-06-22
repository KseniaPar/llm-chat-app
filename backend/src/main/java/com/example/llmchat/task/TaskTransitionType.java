package com.example.llmchat.task;

public enum TaskTransitionType {
    START_PLANNING("Старт задачи"),
    ADVANCE_PLANNING_SUBPHASE("Уточнение → согласование плана"),
    APPROVE_PLAN_TO_EXECUTION("План утверждён → разбор"),
    UPDATE_IN_PHASE("Обновление внутри этапа"),
    EXECUTION_TO_VALIDATION("Разбор → самопроверка"),
    VALIDATION_TO_DONE("Самопроверка → завершение"),
    PAUSE("Пауза"),
    RESUME("Продолжение");

    private final String displayLabel;

    TaskTransitionType(String displayLabel) {
        this.displayLabel = displayLabel;
    }

    public String displayLabel() {
        return displayLabel;
    }
}
