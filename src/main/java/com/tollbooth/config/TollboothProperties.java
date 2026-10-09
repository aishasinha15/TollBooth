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
public record TollboothProperties(Demo demo, List<ModelConfig> models) {

    public record Demo(String appName, String apiKey, BigDecimal dailyBudgetUsd) {
    }

    public record ModelConfig(String name,
                              BigDecimal inputPricePerMillion,
                              BigDecimal outputPricePerMillion) {
    }
}
