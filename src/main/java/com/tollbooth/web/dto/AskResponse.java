package com.tollbooth.web.dto;

import com.tollbooth.domain.CacheResult;

import java.math.BigDecimal;

/**
 * The JSON Tollbooth sends back for POST /v1/ask.
 * piiMasked = how many private values were hidden from the AI (0 = none).
 */
public record AskResponse(
        String answer,
        String model,
        int inputTokens,
        int outputTokens,
        BigDecimal costUsd,
        long latencyMs,
        CacheResult cacheResult,
        int piiMasked
) {
}
