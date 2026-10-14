package com.tollbooth.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ModelCircuitBreakerTest {

    private final MutableClock clock = new MutableClock();
    private final ModelCircuitBreaker breaker = new ModelCircuitBreaker(3, Duration.ofSeconds(60), clock);

    @Test
    void staysClosedBelowTheThreshold() {
        breaker.recordFailure("smart");
        breaker.recordFailure("smart");

        assertThat(breaker.allowRequest("smart")).isTrue();
        assertThat(breaker.state("smart")).isEqualTo(ModelCircuitBreaker.State.CLOSED);
    }

    @Test
    void opensAfterThreeFailuresInARow() {
        breaker.recordFailure("smart");
        breaker.recordFailure("smart");
        boolean opened = breaker.recordFailure("smart");

        assertThat(opened).isTrue();
        assertThat(breaker.allowRequest("smart")).isFalse();
        assertThat(breaker.allowRequest("cheap")).isTrue(); // each model has its own circuit
    }

    @Test
    void aSuccessResetsTheCount() {
        breaker.recordFailure("smart");
        breaker.recordFailure("smart");
        breaker.recordSuccess("smart");
        breaker.recordFailure("smart");

        assertThat(breaker.allowRequest("smart")).isTrue();
    }

    @Test
    void afterTheTimerOneTrialIsAllowedAndSuccessCloses() {
        openIt();
        clock.advance(Duration.ofSeconds(61));

        assertThat(breaker.allowRequest("smart")).isTrue();   // the trial
        assertThat(breaker.allowRequest("smart")).isFalse();  // others wait for its result
        assertThat(breaker.recordSuccess("smart")).isTrue();
        assertThat(breaker.allowRequest("smart")).isTrue();
        assertThat(breaker.state("smart")).isEqualTo(ModelCircuitBreaker.State.CLOSED);
    }

    @Test
    void aFailedTrialOpensItAgain() {
        openIt();
        clock.advance(Duration.ofSeconds(61));
        breaker.allowRequest("smart");

        breaker.recordFailure("smart");

        assertThat(breaker.allowRequest("smart")).isFalse();
        clock.advance(Duration.ofSeconds(61));
        assertThat(breaker.allowRequest("smart")).isTrue();
    }

    @Test
    void snapshotDescribesEachModel() {
        breaker.recordSuccess("cheap");
        openIt();

        assertThat(breaker.snapshot()).containsEntry("cheap", "CLOSED");
        assertThat(breaker.snapshot().get("smart")).startsWith("OPEN until");
    }

    private void openIt() {
        breaker.recordFailure("smart");
        breaker.recordFailure("smart");
        breaker.recordFailure("smart");
    }
}
