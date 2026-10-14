package com.tollbooth.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plain unit test (no Spring, no Redis, no Gemini). A scripted fake AiClient
 * answers or fails in a fixed order, and a fake sleeper records waits instead of sleeping.
 */
class ResilientAiCallerTest {

    private static final AiProviderException BUSY = new AiProviderException("AI provider busy (HTTP 503)", 503, true, null);
    private static final AiProviderException QUOTA = new AiProviderException("rate limit", 503);

    /** Each call takes the next scripted step: either an exception to throw or a text to answer with. */
    private static final class ScriptedAi implements AiClient {
        final Deque<Object> script = new ArrayDeque<>();
        final List<String> modelsCalled = new ArrayList<>();

        ScriptedAi then(Object step) {
            script.add(step);
            return this;
        }

        @Override
        public AiResult generate(String model, String prompt) {
            modelsCalled.add(model);
            Object step = script.removeFirst();
            if (step instanceof AiProviderException e) {
                throw e;
            }
            return new AiResult((String) step, 10, 20);
        }

        @Override
        public float[] embed(String text) {
            throw new UnsupportedOperationException();
        }
    }

    private final List<Long> waits = new ArrayList<>();

    private ResilientAiCaller caller(ScriptedAi ai) {
        return new ResilientAiCaller(ai, 2000, waits::add);
    }

    @Test
    void successNeedsNoRetry() {
        ScriptedAi ai = new ScriptedAi().then("answer");

        ResilientAiCaller.Outcome outcome = caller(ai).call("smart", "cheap", "q");

        assertThat(outcome.model()).isEqualTo("smart");
        assertThat(outcome.notes()).isEmpty();
        assertThat(waits).isEmpty();
    }

    @Test
    void busyOnceThenRetryWorksKeepsTheSmartModel() {
        ScriptedAi ai = new ScriptedAi().then(BUSY).then("answer");

        ResilientAiCaller.Outcome outcome = caller(ai).call("smart", "cheap", "q");

        assertThat(outcome.model()).isEqualTo("smart");
        assertThat(outcome.notes()).contains("retried once after 2000 ms and it worked");
        assertThat(ai.modelsCalled).containsExactly("smart", "smart");
        assertThat(waits).containsExactly(2000L);
    }

    @Test
    void busyTwiceFallsBackToCheap() {
        ScriptedAi ai = new ScriptedAi().then(BUSY).then(BUSY).then("cheap answer");

        ResilientAiCaller.Outcome outcome = caller(ai).call("smart", "cheap", "q");

        assertThat(outcome.model()).isEqualTo("cheap");
        assertThat(outcome.result().text()).isEqualTo("cheap answer");
        assertThat(outcome.notes()).contains("retried once").contains("fell back to cheap");
        assertThat(ai.modelsCalled).containsExactly("smart", "smart", "cheap");
    }

    @Test
    void nonRetryableErrorSkipsTheRetryAndFallsBackStraightAway() {
        ScriptedAi ai = new ScriptedAi().then(QUOTA).then("cheap answer");

        ResilientAiCaller.Outcome outcome = caller(ai).call("smart", "cheap", "q");

        assertThat(outcome.model()).isEqualTo("cheap");
        assertThat(waits).isEmpty();
        assertThat(ai.modelsCalled).containsExactly("smart", "cheap");
    }

    @Test
    void withoutFallbackTheErrorReachesTheCaller() {
        ScriptedAi ai = new ScriptedAi().then(BUSY).then(BUSY);

        assertThatThrownBy(() -> caller(ai).call("cheap", null, "q"))
                .isInstanceOf(AiProviderException.class);
        assertThat(ai.modelsCalled).containsExactly("cheap", "cheap");
    }

    // ---------- with the circuit breaker (threshold 3, open for 60 s) ----------

    @Test
    void afterThreeFailedRequestsTheSmartModelIsSkipped() {
        MutableClock clock = new MutableClock();
        ScriptedAi ai = new ScriptedAi();
        for (int i = 0; i < 3; i++) {
            ai.then(BUSY).then(BUSY).then("cheap answer");   // smart, retry, fallback
        }
        ai.then("cheap answer");                              // 4th request: cheap only
        ResilientAiCaller caller = new ResilientAiCaller(ai, 2000, waits::add,
                new ModelCircuitBreaker(3, java.time.Duration.ofSeconds(60), clock));

        for (int i = 0; i < 3; i++) {
            caller.call("smart", "cheap", "q");
        }
        ai.modelsCalled.clear();
        waits.clear();
        ResilientAiCaller.Outcome fourth = caller.call("smart", "cheap", "q");

        assertThat(ai.modelsCalled).containsExactly("cheap");  // smart never called
        assertThat(waits).isEmpty();                           // and no 2 s wait
        assertThat(fourth.model()).isEqualTo("cheap");
        assertThat(fourth.notes()).contains("smart skipped: circuit open after 3 failures in a row");
    }

    @Test
    void whenTheTimerRunsOutOneRequestTriesTheSmartModelAgain() {
        MutableClock clock = new MutableClock();
        ModelCircuitBreaker breaker = new ModelCircuitBreaker(3, java.time.Duration.ofSeconds(60), clock);
        breaker.recordFailure("smart");
        breaker.recordFailure("smart");
        breaker.recordFailure("smart");
        clock.advance(java.time.Duration.ofSeconds(61));
        ScriptedAi ai = new ScriptedAi().then("smart answer");

        ResilientAiCaller.Outcome outcome = new ResilientAiCaller(ai, 2000, waits::add, breaker)
                .call("smart", "cheap", "q");

        assertThat(outcome.model()).isEqualTo("smart");
        assertThat(breaker.state("smart")).isEqualTo(ModelCircuitBreaker.State.CLOSED);
    }

    @Test
    void anOpenCircuitIsIgnoredWhenThereIsNoFallback() {
        MutableClock clock = new MutableClock();
        ModelCircuitBreaker breaker = new ModelCircuitBreaker(1, java.time.Duration.ofSeconds(60), clock);
        breaker.recordFailure("cheap");
        ScriptedAi ai = new ScriptedAi().then("answer");

        ResilientAiCaller.Outcome outcome = new ResilientAiCaller(ai, 2000, waits::add, breaker)
                .call("cheap", null, "q");

        assertThat(outcome.model()).isEqualTo("cheap"); // the only option is still tried
    }

    @Test
    void failuresThatAreNotOutagesDoNotOpenTheCircuit() {
        MutableClock clock = new MutableClock();
        ModelCircuitBreaker breaker = new ModelCircuitBreaker(1, java.time.Duration.ofSeconds(60), clock);
        AiProviderException blocked = new AiProviderException("AI provider returned no text", 502);
        ScriptedAi ai = new ScriptedAi().then(blocked).then("cheap answer");

        new ResilientAiCaller(ai, 2000, waits::add, breaker).call("smart", "cheap", "q");

        assertThat(breaker.allowRequest("smart")).isTrue();
    }
}
