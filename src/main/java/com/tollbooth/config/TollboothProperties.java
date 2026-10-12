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
public record TollboothProperties(Ai ai, RateLimit rateLimit, Demo demo, List<ModelConfig> models) {

    /** Settings for talking to Gemini. */
    public record Ai(String baseUrl,
                     String apiKey,
                     String defaultModel,
                     String systemPrompt,
                     int connectTimeoutMs,
                     int readTimeoutMs) {
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

    public record Demo(String appName, String apiKey, BigDecimal dailyBudgetUsd) {
    }

    public record ModelConfig(String name,
                              BigDecimal inputPricePerMillion,
                              BigDecimal outputPricePerMillion) {
    }
}
