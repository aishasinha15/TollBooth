package com.tollbooth.ratelimit;

/**
 * Answer from the token bucket.
 *
 * @param allowed      true if the question may go ahead
 * @param remaining    questions left right now (-1 = unknown, Redis was unreachable)
 * @param retryAfterMs when rejected, how long until the next token arrives
 */
public record RateLimitResult(boolean allowed, long remaining, long retryAfterMs) {

    /** Used when Redis is down and we let the request through ("fail open"). */
    public static RateLimitResult unknown() {
        return new RateLimitResult(true, -1, 0);
    }
}
