package com.example.llmchat.task;

public enum TaskPhase {
    PLANNING("planning", "Подготовка плана"),
    EXECUTION("execution", "Разбор тем"),
    VALIDATION("validation", "Самопроверка"),
    DONE("done", "Тема пройдена");

    private final String id;
    private final String displayLabel;

    TaskPhase(String id, String displayLabel) {
        this.id = id;
        this.displayLabel = displayLabel;
    }

    public String id() {
        return id;
    }

    public String displayLabel() {
        return displayLabel;
    }

    public static TaskPhase fromId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Неизвестный этап задачи: " + id);
        }
        String normalized = id.trim().toLowerCase();
        for (TaskPhase phase : values()) {
            if (phase.id.equals(normalized)) {
                return phase;
            }
        }
        throw new IllegalArgumentException("Неизвестный этап задачи: " + id);
    }
}
