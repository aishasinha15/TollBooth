package com.tollbooth.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * A circuit breaker per AI model, like the breaker in a fuse box: when a model keeps failing,
 * it "trips" and Tollbooth stops calling that model for a while.
 *
 *   CLOSED     normal: every request may use the model. Count failures in a row.
 *   OPEN       after failure-threshold failures in a row: skip the model until the timer runs out.
 *              Requests go straight to the fallback model: no wasted calls, no 2 s retry waits.
 *   HALF_OPEN  the timer ran out: let ONE request try the model.
 *              It works -> CLOSED again. It fails -> OPEN for another round.
 *
 * Only "the provider is unavailable" failures count (HTTP 503 from Tollbooth's point of view:
 * busy, rate limited, timed out). A bad answer to one question says nothing about the model's health.
 *
 * State lives in this server's memory: with several Tollbooth servers, each keeps its own.
 * All methods are synchronized, so concurrent requests see a consistent state.
 */
public class ModelCircuitBreaker {

    enum State { CLOSED, OPEN, HALF_OPEN }

    private static final class Circuit {
        State state = State.CLOSED;
        int failuresInARow;
        Instant openUntil;
        Instant trialStartedAt;
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final int failureThreshold;
    private final Duration openFor;
    private final Clock clock;
    private final Map<String, Circuit> circuits = new LinkedHashMap<>();

    public ModelCircuitBreaker(int failureThreshold, Duration openFor, Clock clock) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openFor = openFor;
        this.clock = clock;
    }

    /** May a request use this model right now? */
    public synchronized boolean allowRequest(String model) {
        Circuit c = circuit(model);
        Instant now = clock.instant();
        switch (c.state) {
            case CLOSED:
                return true;
            case OPEN:
                if (now.isBefore(c.openUntil)) {
                    return false;
                }
                c.state = State.HALF_OPEN;   // timer ran out: this request is the trial
                c.trialStartedAt = now;
                return true;
            case HALF_OPEN:
            default:
                // One trial at a time. If a trial never reported back, allow a new one after a while.
                if (c.trialStartedAt != null && !now.isBefore(c.trialStartedAt.plus(openFor))) {
                    c.trialStartedAt = now;
                    return true;
                }
                return false;
        }
    }

    /** The model answered: close the circuit. Returns true if it had been open (worth a log line). */
    public synchronized boolean recordSuccess(String model) {
        Circuit c = circuit(model);
        boolean wasOpen = c.state != State.CLOSED;
        c.state = State.CLOSED;
        c.failuresInARow = 0;
        c.openUntil = null;
        c.trialStartedAt = null;
        return wasOpen;
    }

    /** The model was unavailable. Returns true if this failure opened the circuit. */
    public synchronized boolean recordFailure(String model) {
        Circuit c = circuit(model);
        Instant now = clock.instant();
        if (c.state == State.HALF_OPEN) {
            open(c, now);                 // the trial failed: wait another round
            return true;
        }
        c.failuresInARow++;
        if (c.state == State.CLOSED && c.failuresInARow >= failureThreshold) {
            open(c, now);
            return true;
        }
        return false;
    }

    /** When an open circuit lets the next trial through, as a local clock time for messages. */
    public synchronized String nextTrialTime(String model) {
        Instant until = circuit(model).openUntil;
        return until == null ? "now" : LocalTime.ofInstant(until, ZoneId.systemDefault()).format(TIME);
    }

    public int failureThreshold() {
        return failureThreshold;
    }

    /** Every model seen so far and its state, for GET /v1/status. */
    public synchronized Map<String, String> snapshot() {
        Map<String, String> states = new TreeMap<>();
        Instant now = clock.instant();
        circuits.forEach((model, c) -> {
            String text = switch (c.state) {
                case CLOSED -> c.failuresInARow == 0 ? "CLOSED" : "CLOSED (" + c.failuresInARow + " failure(s) in a row)";
                case OPEN -> now.isBefore(c.openUntil)
                        ? "OPEN until " + nextTrialTime(model)
                        : "OPEN (next request is a trial)";
                case HALF_OPEN -> "HALF_OPEN (trial request running)";
            };
            states.put(model, text);
        });
        return states;
    }

    State state(String model) {
        return circuit(model).state;
    }

    private void open(Circuit c, Instant now) {
        c.state = State.OPEN;
        c.openUntil = now.plus(openFor);
        c.trialStartedAt = null;
    }

    private Circuit circuit(String model) {
        return circuits.computeIfAbsent(model, m -> new Circuit());
    }
}
