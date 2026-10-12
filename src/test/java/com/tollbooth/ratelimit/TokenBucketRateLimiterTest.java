package com.tollbooth.ratelimit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * An INTEGRATION test: runs the real Lua script against the real Redis
 * from docker-compose (localhost:6379). If Redis isn't running, these tests
 * are skipped instead of failing. Run `docker compose up -d` first.
 *
 * Every test uses a random key, so tests never touch each other or the app's real buckets.
 */
class TokenBucketRateLimiterTest {

    private static LettuceConnectionFactory connectionFactory;
    private static TokenBucketRateLimiter limiter;
    private static boolean redisAvailable;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        try {
            template.hasKey("tb:test:ping"); // any command proves Redis is reachable
            redisAvailable = true;
        } catch (Exception e) {
            redisAvailable = false;
        }
        limiter = new TokenBucketRateLimiter(template);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    private static String newKey() {
        return "tb:test:" + UUID.randomUUID();
    }

    @Test
    void allowsUpToCapacityThenRejects() {
        assumeTrue(redisAvailable, "Redis not running; start it with docker compose up -d");
        String key = newKey();

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryConsume(key, 5, Duration.ofHours(1)).allowed()).isTrue();
        }
        RateLimitResult sixth = limiter.tryConsume(key, 5, Duration.ofHours(1));

        assertThat(sixth.allowed()).isFalse();
        assertThat(sixth.remaining()).isZero();
        assertThat(sixth.retryAfterMs()).isPositive();
    }

    @Test
    void remainingCountsDown() {
        assumeTrue(redisAvailable, "Redis not running");
        String key = newKey();

        assertThat(limiter.tryConsume(key, 3, Duration.ofHours(1)).remaining()).isEqualTo(2);
        assertThat(limiter.tryConsume(key, 3, Duration.ofHours(1)).remaining()).isEqualTo(1);
        assertThat(limiter.tryConsume(key, 3, Duration.ofHours(1)).remaining()).isEqualTo(0);
    }

    @Test
    void refillsOverTime() throws InterruptedException {
        assumeTrue(redisAvailable, "Redis not running");
        String key = newKey();
        Duration refill = Duration.ofMillis(200);

        limiter.tryConsume(key, 2, refill);
        limiter.tryConsume(key, 2, refill);
        assertThat(limiter.tryConsume(key, 2, refill).allowed()).isFalse();

        Thread.sleep(250); // one token comes back after 200 ms

        assertThat(limiter.tryConsume(key, 2, refill).allowed()).isTrue();
    }

    @Test
    void differentUsersHaveSeparateBuckets() {
        assumeTrue(redisAvailable, "Redis not running");
        String userA = newKey();
        String userB = newKey();

        limiter.tryConsume(userA, 1, Duration.ofHours(1));
        assertThat(limiter.tryConsume(userA, 1, Duration.ofHours(1)).allowed()).isFalse();
        assertThat(limiter.tryConsume(userB, 1, Duration.ofHours(1)).allowed()).isTrue();
    }

    /**
     * The important one: 100 requests at the same instant against a bucket of 20.
     * If "check then take" were two separate steps, several threads could read
     * the same count and more than 20 would slip through. The Lua script is atomic,
     * so exactly 20 must pass, every single run.
     */
    @Test
    void exactlyCapacityPassesUnderConcurrency() throws Exception {
        assumeTrue(redisAvailable, "Redis not running");
        String key = newKey();
        int threads = 100;
        int capacity = 20;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startSignal = new CountDownLatch(1); // holds every thread at the start line
        List<Future<Boolean>> results = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                startSignal.await();
                return limiter.tryConsume(key, capacity, Duration.ofHours(1)).allowed();
            }));
        }
        startSignal.countDown(); // go!

        int allowed = 0;
        for (Future<Boolean> result : results) {
            if (result.get(10, TimeUnit.SECONDS)) {
                allowed++;
            }
        }
        pool.shutdown();

        assertThat(allowed).isEqualTo(capacity);
    }
}
