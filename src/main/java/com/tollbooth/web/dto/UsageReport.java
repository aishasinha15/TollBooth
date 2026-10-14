package com.tollbooth.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One app's day in numbers (Phase 7): GET /v1/usage
 *
 * @param estimatedSavedByCacheUsd cache hits x the average cost of an AI answer that day.
 *                                 An estimate: we can't know exactly what a hit would have cost.
 * @param circuitSkips             requests that skipped an unavailable model (circuit open)
 */
public record UsageReport(
        String app,
        LocalDate date,
        int questions,
        int answeredByAi,
        int cacheHitsExact,
        int cacheHitsSemantic,
        double cacheHitRatePercent,
        BigDecimal spentUsd,
        BigDecimal dailyBudgetUsd,
        double budgetUsedPercent,
        BigDecimal averageAiAnswerCostUsd,
        BigDecimal estimatedSavedByCacheUsd,
        int retries,
        int fallbacks,
        int circuitSkips,
        int downgradedForBudget,
        int answersCutOff,
        int questionsWithPrivateData,
        long averageLatencyMsCacheHit,
        long averageLatencyMsAiAnswer,
        List<ModelUsage> byModel) {

    /** AI answers per model (cache hits not included). */
    public record ModelUsage(String model, int answers, long inputTokens, long outputTokens, BigDecimal costUsd) {
    }
}
