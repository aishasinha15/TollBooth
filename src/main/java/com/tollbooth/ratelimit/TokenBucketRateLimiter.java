package com.tollbooth.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Token bucket rate limiter backed by Redis.
 *
 * The rule: each user has a bucket of N tokens. Every question takes one.
 * Tokens come back one at a time at a fixed pace. Empty bucket = "please wait".
 *
 * All the logic runs inside Redis as one Lua script (resources/scripts/token_bucket.lua),
 * which makes "refill, check, take a token, save" atomic. Because the state lives
 * in Redis, not in Java memory, the limit still holds when several Tollbooth
 * servers run side by side.
 */
@Component
public class TokenBucketRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    /**
     * Spring sends the script with EVALSHA (just its fingerprint) and only falls back
     * to sending the full script text the first time Redis hasn't seen it.
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT =
            RedisScript.of(new ClassPathResource("scripts/token_bucket.lua"), List.class);

    private final StringRedisTemplate redis;

    public TokenBucketRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Try to take one token from the bucket stored at bucketKey.
     */
    public RateLimitResult tryConsume(String bucketKey, int capacity, Duration refillEvery) {
        try {
            List<?> result = redis.execute(SCRIPT, List.of(bucketKey),
                    String.valueOf(capacity), String.valueOf(refillEvery.toMillis()));

            boolean allowed = toLong(result.get(0)) == 1L;
            long remaining = toLong(result.get(1));
            long retryAfterMs = toLong(result.get(2));
            return new RateLimitResult(allowed, remaining, retryAfterMs);

        } catch (DataAccessException e) {
            // Redis is down. We "fail open": let the question through rather than
            // take the whole assistant offline. (A bank might choose "fail closed".)
            log.warn("Rate limiter unavailable, allowing request: {}", e.getMessage());
            return RateLimitResult.unknown();
        }
    }

    /** Redis returns integers as Long; this also copes if one arrives as text. */
    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
