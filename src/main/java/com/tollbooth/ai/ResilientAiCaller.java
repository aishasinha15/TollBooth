package com.tollbooth.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Calls the AI and copes with failures. Three layers:
 *
 *   1. CIRCUIT BREAKER: if a model has failed several requests in a row, skip it for a while and
 *      go straight to the fallback (no wasted calls, no retry waits). See ModelCircuitBreaker.
 *   2. RETRY: if a model is only busy (Google 500/503), wait a moment and ask it once more.
 *   3. FALLBACK: if the model still fails and a fallback model is given, ask that one
 *      (it also gets one retry).
 *
 * Examples:
 *   smart -> 503 -> wait 2 s -> smart -> answer                   (retry worked)
 *   smart -> 503 -> wait 2 s -> smart -> 503 -> cheap -> answer   (fell back; 1 failure counted)
 *   ...3 such requests in a row: smart's circuit opens...
 *   cheap -> answer                                              (smart skipped for 60 s)
 *
 * Kept out of AskService so it can be unit-tested on its own with a fake AiClient and a fake clock.
 */
@Component
public class ResilientAiCaller {

    private static final Logger log = LoggerFactory.getLogger(ResilientAiCaller.class);

    /** What happened: the answer, which model actually gave it, and notes for the bill. */
    public record Outcome(AiResult result, String model, String notes) {
    }

    /** Waiting is behind an interface so tests don't really sleep. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final AiClient aiClient;
    private final long retryDelayMs;
    private final Sleeper sleeper;
    private final ModelCircuitBreaker breaker;

    @Autowired // several constructors: this tells Spring which one to use
    public ResilientAiCaller(AiClient aiClient,
                             @Value("${tollbooth.ai.retry-delay-ms:2000}") long retryDelayMs,
                             @Value("${tollbooth.ai.circuit-breaker.failure-threshold:3}") int failureThreshold,
                             @Value("${tollbooth.ai.circuit-breaker.open-seconds:60}") long openSeconds) {
        this(aiClient, retryDelayMs, Thread::sleep,
                new ModelCircuitBreaker(failureThreshold, Duration.ofSeconds(openSeconds), Clock.systemUTC()));
    }

    /** For tests: no circuit breaker effect unless a test passes one in. */
    ResilientAiCaller(AiClient aiClient, long retryDelayMs, Sleeper sleeper) {
        this(aiClient, retryDelayMs, sleeper, new ModelCircuitBreaker(Integer.MAX_VALUE, Duration.ZERO, Clock.systemUTC()));
    }

    ResilientAiCaller(AiClient aiClient, long retryDelayMs, Sleeper sleeper, ModelCircuitBreaker breaker) {
        this.aiClient = aiClient;
        this.retryDelayMs = retryDelayMs;
        this.sleeper = sleeper;
        this.breaker = breaker;
    }

    /**
     * @param model         the model to ask first
     * @param fallbackModel asked if the first model fails or is skipped; null = no fallback
     */
    public Outcome call(String model, String fallbackModel, String prompt) {
        List<String> notes = new ArrayList<>();
        boolean hasFallback = fallbackModel != null && !fallbackModel.equals(model);

        // 1. Circuit open? Skip the model entirely (only when there is somewhere else to go)
        if (hasFallback && !breaker.allowRequest(model)) {
            notes.add(model + " skipped: circuit open after " + breaker.failureThreshold()
                    + " failures in a row, next try after " + breaker.nextTrialTime(model));
            AiResult result = attempt(fallbackModel, prompt, notes);
            return new Outcome(result, fallbackModel, String.join("; ", notes));
        }

        // 2-3. Try the model (with one retry), then the fallback
        try {
            return new Outcome(attempt(model, prompt, notes), model, String.join("; ", notes));
        } catch (AiProviderException first) {
            if (!hasFallback) {
                throw first;
            }
            log.warn("{} failed ({}), falling back to {}", model, first.getMessage(), fallbackModel);
            notes.add(model + " failed (" + first.getMessage() + "), fell back to " + fallbackModel);
            AiResult result = attempt(fallbackModel, prompt, notes);
            return new Outcome(result, fallbackModel, String.join("; ", notes));
        }
    }

    /** The model's circuits, for GET /v1/status. */
    public Map<String, String> circuitStates() {
        return breaker.snapshot();
    }

    /** One model, one retry, and the circuit breaker's bookkeeping. */
    private AiResult attempt(String model, String prompt, List<String> notes) {
        try {
            AiResult result = callWithOneRetry(model, prompt, notes);
            if (breaker.recordSuccess(model)) {
                log.info("Circuit CLOSED for {}: it answered again", model);
            }
            return result;
        } catch (AiProviderException e) {
            // Only "unavailable" failures (busy, rate limited, timed out) say the model is unhealthy
            if (e.getHttpStatus() == 503 && breaker.recordFailure(model)) {
                log.warn("Circuit OPEN for {}: skipping it until {}", model, breaker.nextTrialTime(model));
            }
            throw e;
        }
    }

    private AiResult callWithOneRetry(String model, String prompt, List<String> notes) {
        try {
            return aiClient.generate(model, prompt);
        } catch (AiProviderException busy) {
            if (!busy.isRetryable() || retryDelayMs <= 0) {
                throw busy;
            }
            log.info("{} busy ({}), retrying once in {} ms", model, busy.getMessage(), retryDelayMs);
            pause();
            try {
                AiResult result = aiClient.generate(model, prompt);
                notes.add(model + " busy, retried once after " + retryDelayMs + " ms and it worked");
                return result;
            } catch (AiProviderException again) {
                notes.add(model + " busy, retried once after " + retryDelayMs + " ms");
                throw again;
            }
        }
    }

    private void pause() {
        try {
            sleeper.sleep(retryDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // keep the "interrupted" flag for whoever stops us
            throw new AiProviderException("Interrupted while waiting to retry", 503, e);
        }
    }
}
