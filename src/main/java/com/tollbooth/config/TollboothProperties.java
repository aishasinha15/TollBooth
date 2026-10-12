package com.tollbooth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * Typed view of the "tollbooth:" section in application.yml.
 * Spring fills these records automatically at startup
 * (kebab-case keys like daily-budget-usd map to dailyBudgetUsd).
 */
@ConfigurationProperties(prefix = "tollbooth")
public record TollboothProperties(Ai ai, Demo demo, List<ModelConfig> models) {

    /** Settings for talking to Gemini. */
    public record Ai(String baseUrl,
                     String apiKey,
                     String defaultModel,
                     String systemPrompt,
                     int connectTimeoutMs,
                     int readTimeoutMs) {
    }

    public record Demo(String appName, String apiKey, BigDecimal dailyBudgetUsd) {
    }

    public record ModelConfig(String name,
                              BigDecimal inputPricePerMillion,
                              BigDecimal outputPricePerMillion) {
    }
}
