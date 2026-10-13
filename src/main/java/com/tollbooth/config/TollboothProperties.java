package com.tollbooth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * Typed view of the "tollbooth:" section in application.yml.
 * Spring fills these records automatically at startup
 * (kebab-case keys like daily-budget-usd map to dailyBudgetUsd,
 * and values like "12s" become a Duration).
 */
@ConfigurationProperties(prefix = "tollbooth")
public record TollboothProperties(Ai ai, RateLimit rateLimit, Budget budget, Cache cache,
                                  Demo demo, List<ModelConfig> models) {

    /**
     * Settings for talking to Gemini.
     *
     * @param cheapModel            used for EASY questions, fallbacks and when the budget runs low
     * @param smartModel            used for HARD questions while the budget allows
     * @param estimatedOutputTokens answer length assumed when reserving budget before a call
     * @param embeddingModel        turns text into a list of numbers for the semantic cache
     * @param embeddingDimensions   how many numbers per embedding (smaller = less storage)
     */
    public record Ai(String baseUrl,
                     String apiKey,
                     String cheapModel,
                     String smartModel,
                     String systemPrompt,
                     int connectTimeoutMs,
                     int readTimeoutMs,
                     int estimatedOutputTokens,
                     String embeddingModel,
                     int embeddingDimensions) {
    }

    /** Token bucket sizes per user tier. */
    public record RateLimit(Bucket regular, Bucket premium) {
    }

    /**
     * @param capacity    most questions a user can ask in a burst
     * @param refillEvery how long it takes to earn back one question
     */
    public record Bucket(int capacity, Duration refillEvery) {
    }

    /**
     * @param warnAtPercent    show a warning from this % of the daily budget
     * @param hardStopPercent  refuse AI calls from this % (above 100, so cheap answers continue a bit past budget)
     * @param timezone         when "today" starts and ends, e.g. Asia/Kolkata
     */
    public record Budget(int warnAtPercent, int hardStopPercent, String timezone) {
    }

    /**
     * @param enabled            turn the whole cache on or off
     * @param ttl                how long a saved answer stays valid, e.g. 24h
     * @param semanticThreshold   below this similarity: not the same question (miss)
     * @param autoAcceptThreshold at or above this: reuse straight away (near-identical wording).
     *                            In between ("grey zone"): the cheap model confirms YES/NO first.
     * @param maxSemanticEntries  how many recent questions per app the semantic cache compares against
     */
    public record Cache(boolean enabled, Duration ttl, double semanticThreshold, double autoAcceptThreshold,
                        int maxSemanticEntries) {
    }

    public record Demo(String appName, String apiKey, BigDecimal dailyBudgetUsd) {
    }

    public record ModelConfig(String name,
                              BigDecimal inputPricePerMillion,
                              BigDecimal outputPricePerMillion) {
    }
}
