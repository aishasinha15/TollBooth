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
                                  Demo demo, List<ModelConfig> models, DuplicateChecker duplicateChecker) {

    /**
     * Settings for talking to Gemini.
     *
     * @param cheapModel          used for EASY questions, fallbacks and when the budget runs low
     * @param smartModel          used for HARD questions while the budget allows
     * @param maxOutputTokens     longest answer the AI may give (thinking included). The budget
     *                            reserves this worst case, so it can never be overshot (Phase 7)
     * @param embeddingModel      turns text into a list of numbers for the semantic cache
     * @param embeddingDimensions how many numbers per embedding (smaller = less storage)
     * @param retryDelayMs        wait before asking a busy model once more (Phase 7)
     */
    public record Ai(String baseUrl,
                     String apiKey,
                     String cheapModel,
                     String smartModel,
                     String systemPrompt,
                     int connectTimeoutMs,
                     int readTimeoutMs,
                     int maxOutputTokens,
                     String embeddingModel,
                     int embeddingDimensions,
                     long retryDelayMs) {
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
     * @param autoAcceptThreshold at or above this: reuse without a check. Phase 7 measurement showed
     *                            no value is safe, so it is set high enough (0.99) to switch it off.
     * @param maxSemanticEntries  how many recent questions per app the semantic cache compares against
     * @param judge               who confirms a similar question needs the same answer:
     *                            "duplicate-model" (free, local) or "gemini" (YES/NO from the cheap model)
     * @param duplicateThreshold  duplicate-model score (0 to 1) needed to count as "same answer"
     * @param rerankTopK          how many of the most similar cached questions the judge looks at
     *                            (only when the judge is free; the Gemini judge looks at 1)
     * @param checkEasyQuestions  let the paid Gemini judge check EASY questions too. Measured in Phase 7:
     *                            about 6 in 10 checks say YES, and a check costs about 1/40 of a cheap answer
     */
    public record Cache(boolean enabled, Duration ttl, double semanticThreshold, double autoAcceptThreshold,
                        int maxSemanticEntries, String judge, double duplicateThreshold, int rerankTopK,
                        boolean checkEasyQuestions) {
    }

    /**
     * The duplicate-question model container (Phase 7), see duplicate-checker/ and docker-compose.yml.
     *
     * @param baseUrl   where it listens, e.g. http://localhost:8001
     * @param timeoutMs give up after this long (then the cache counts it as a miss)
     */
    public record DuplicateChecker(String baseUrl, int timeoutMs) {
    }

    public record Demo(String appName, String apiKey, BigDecimal dailyBudgetUsd) {
    }

    public record ModelConfig(String name,
                              BigDecimal inputPricePerMillion,
                              BigDecimal outputPricePerMillion) {
    }
}
