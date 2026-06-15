package com.example.llmchat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TokenScenarioStreamEvent(
        String event,
        String id,
        String title,
        String description,
        Integer modelContextLimit,
        Integer turn,
        String content,
        TokenDemoStep step,
        String outcome,
        Boolean failed,
        String liveApiError,
        Integer liveApiStatusCode,
        String variant,
        Integer messagesSummarized,
        Integer summaryTokens,
        String summaryPreview,
        CompressionVariantResult variantResult,
        CompressionCompareResponse compareResult,
        StrategyVariantResult strategyVariantResult,
        StrategyCompareResponse strategyCompareResult,
        Map<String, String> facts,
        List<BranchInfoDto> branches) {

    public static TokenScenarioStreamEvent start(
            String id, String title, String description, int modelContextLimit) {
        return new TokenScenarioStreamEvent(
                "start", id, title, description, modelContextLimit,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    public static TokenScenarioStreamEvent user(int turn, String content) {
        return new TokenScenarioStreamEvent(
                "user", null, null, null, null,
                turn, content, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    public static TokenScenarioStreamEvent turn(int turn, String content, TokenDemoStep step) {
        return new TokenScenarioStreamEvent(
                "turn", null, null, null, null,
                turn, content, step, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    public static TokenScenarioStreamEvent done(
            String outcome, boolean failed, String liveApiError, Integer liveApiStatusCode) {
        return new TokenScenarioStreamEvent(
                "done", null, null, null, null,
                null, null, null, outcome, failed, liveApiError, liveApiStatusCode,
                null, null, null, null, null, null, null, null, null, null);
    }

    public static TokenScenarioStreamEvent compareStart(String title, String description, int modelContextLimit) {
        return new TokenScenarioStreamEvent(
                "compare_start", "compression", title, description, modelContextLimit,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    public static TokenScenarioStreamEvent variantStart(String variant, String title, int modelContextLimit) {
        return new TokenScenarioStreamEvent(
                "variant_start", variant, title, null, modelContextLimit,
                null, null, null, null, null, null, null,
                variant, null, null, null, null, null, null, null, null, null);
    }

    public static TokenScenarioStreamEvent compressed(
            int turn,
            int messagesSummarized,
            int summaryTokens,
            String summaryPreview) {
        return new TokenScenarioStreamEvent(
                "compressed", null, null, null, null,
                turn, null, null, null, null, null, null,
                null, messagesSummarized, summaryTokens, summaryPreview, null, null, null, null, null, null);
    }

    public static TokenScenarioStreamEvent variantDone(CompressionVariantResult variantResult) {
        return new TokenScenarioStreamEvent(
                "variant_done", variantResult.mode(), variantResult.title(), null, null,
                null, null, null, null, variantResult.failed(), variantResult.liveApiError(),
                variantResult.liveApiStatusCode(),
                variantResult.mode(), null, null, null, variantResult, null, null, null, null, null);
    }

    public static TokenScenarioStreamEvent compareDone(CompressionCompareResponse compareResult) {
        return new TokenScenarioStreamEvent(
                "compare_done", "compression", null, null, compareResult.modelContextLimit(),
                null, null, null, null, null, null, null,
                null, null, null, null, null, compareResult, null, null, null, null);
    }

    public static TokenScenarioStreamEvent strategyCompareStart(String title, String description, int modelContextLimit) {
        return new TokenScenarioStreamEvent(
                "strategy_compare_start", "strategies", title, description, modelContextLimit,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    public static TokenScenarioStreamEvent strategyStart(String variant, String title, int modelContextLimit) {
        return new TokenScenarioStreamEvent(
                "strategy_start", variant, title, null, modelContextLimit,
                null, null, null, null, null, null, null,
                variant, null, null, null, null, null, null, null, null, null);
    }

    public static TokenScenarioStreamEvent factsUpdated(int turn, Map<String, String> facts) {
        return new TokenScenarioStreamEvent(
                "facts_updated", null, null, null, null,
                turn, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, facts, null);
    }

    public static TokenScenarioStreamEvent branchCreated(int turn, List<BranchInfoDto> branches) {
        return new TokenScenarioStreamEvent(
                "branch_created", null, null, null, null,
                turn, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, branches);
    }

    public static TokenScenarioStreamEvent strategyVariantDone(StrategyVariantResult variantResult) {
        return new TokenScenarioStreamEvent(
                "strategy_variant_done", variantResult.mode(), variantResult.title(), null, null,
                null, null, null, null, variantResult.failed(), variantResult.liveApiError(),
                variantResult.liveApiStatusCode(),
                variantResult.mode(), null, null, null, null, null, variantResult, null, null, null);
    }

    public static TokenScenarioStreamEvent strategyCompareDone(StrategyCompareResponse compareResult) {
        return new TokenScenarioStreamEvent(
                "strategy_compare_done", "strategies", null, null, compareResult.modelContextLimit(),
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, compareResult, null, null);
    }
}
