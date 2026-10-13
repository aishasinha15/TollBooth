package com.tollbooth.web.dto;

import com.tollbooth.domain.CacheResult;
import com.tollbooth.routing.Difficulty;

import java.math.BigDecimal;

/**
 * The JSON Tollbooth sends back for POST /v1/ask.
 *
 * piiMasked          how many private values were hidden from the AI (0 = none)
 * difficulty         EASY or HARD, as judged by the router
 * routeReason        why that model was used (routing, budget downgrade, fallback)
 * budgetUsedPercent  how much of the app's daily AI budget is used, after this question
 * budgetWarning      true once usage passes the warning level (80% by default)
 */
public record AskResponse(
        String answer,
        String model,
        int inputTokens,
        int outputTokens,
        BigDecimal costUsd,
        long latencyMs,
        CacheResult cacheResult,
        int piiMasked,
        Difficulty difficulty,
        String routeReason,
        int budgetUsedPercent,
        boolean budgetWarning
) {
}
