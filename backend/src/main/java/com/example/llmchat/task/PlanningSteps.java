package com.example.llmchat.task;

public final class PlanningSteps {

    static final String CLARIFICATION = "Уточнение целей и ожиданий";
    static final String AGREEMENT = "Согласование плана";

    private PlanningSteps() {
    }

    public static boolean isAgreement(String currentStep, String expectedAction) {
        return containsAgreementMarker(currentStep) || containsAgreementMarker(expectedAction);
    }

    private static boolean containsAgreementMarker(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase();
        return lower.contains("согласован") || lower.contains("подтвержден")
                || (lower.contains("план") && lower.contains("предлож"));
    }
}
