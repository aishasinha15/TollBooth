package com.tollbooth.ratelimit;

/** Thrown when a user's bucket is empty. Becomes HTTP 429 Too Many Requests. */
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterMs;

    public RateLimitExceededException(long retryAfterMs) {
        super("Rate limit exceeded");
        this.retryAfterMs = retryAfterMs;
    }

    public long getRetryAfterMs() {
        return retryAfterMs;
    }

    /** Rounded up to whole seconds, at least 1 (the Retry-After header uses seconds). */
    public long getRetryAfterSeconds() {
        return Math.max(1, (retryAfterMs + 999) / 1000);
    }
}
