package com.tollbooth.budget;

import com.tollbooth.config.TollboothProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
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
 * INTEGRATION test against the real Redis from docker-compose (skipped if it isn't running).
 * Numbers are micro-dollars: budget 1000, hard stop 1200, smart estimate 400, cheap estimate 50.
 */
class BudgetGuardTest {

    private static final long BUDGET = 1000;
    private static final long HARD_STOP = 1200;

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate template;
    private static BudgetGuard guard;
    private static boolean redisAvailable;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        template = new StringRedisTemplate(connectionFactory);
        try {
            template.hasKey("tb:test:ping");
            redisAvailable = true;
        } catch (Exception e) {
            redisAvailable = false;
        }
        TollboothProperties props = new TollboothProperties(null, null,
                new TollboothProperties.Budget(80, 120, "Asia/Kolkata"), null, null, null);
        guard = new BudgetGuard(template, props);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    private static String newKey() {
        return "tb:test:spend:" + UUID.randomUUID();
    }

    private BudgetReservation reserveSmart(String key) {
        return guard.reserve(key, BUDGET, HARD_STOP, true, 400, 50);
    }

    @Test
    void smartModelWhileItFitsThenDowngrades() {
        assumeTrue(redisAvailable, "Redis not running; start it with docker compose up -d");
        String key = newKey();

        assertThat(reserveSmart(key).smartAllowed()).isTrue();   // spent 400
        assertThat(reserveSmart(key).smartAllowed()).isTrue();   // spent 800
        BudgetReservation third = reserveSmart(key);             // 800 + 400 > 1000
        assertThat(third.allowed()).isTrue();
        assertThat(third.smartAllowed()).isFalse();              // downgraded to cheap
        assertThat(third.reservedMicros()).isEqualTo(50);
    }

    @Test
    void stopsAtHardLimit() {
        assumeTrue(redisAvailable, "Redis not running");
        String key = newKey();
        template.opsForValue().set(key, String.valueOf(HARD_STOP));

        assertThat(reserveSmart(key).allowed()).isFalse();
    }

    @Test
    void settleReplacesEstimateWithRealCost() {
        assumeTrue(redisAvailable, "Redis not running");
        String key = newKey();

        BudgetReservation r = reserveSmart(key);         // holds 400
        long total = guard.settle(r, 130);               // real cost was 130

        assertThat(total).isEqualTo(130);
        assertThat(template.opsForValue().get(key)).isEqualTo("130");
    }

    @Test
    void releaseGivesTheEstimateBack() {
        assumeTrue(redisAvailable, "Redis not running");
        String key = newKey();

        BudgetReservation r = reserveSmart(key);
        guard.release(r);

        assertThat(template.opsForValue().get(key)).isEqualTo("0");
    }

    @Test
    void toMicrosRoundsUp() {
        assertThat(BudgetGuard.toMicros(new BigDecimal("0.0001453"))).isEqualTo(146);
        assertThat(BudgetGuard.toMicros(new BigDecimal("5.00"))).isEqualTo(5_000_000);
        assertThat(BudgetGuard.toMicros(null)).isZero();
    }

    /**
     * 50 questions at once, each wanting the smart model (estimate 100) with a budget of 1000.
     * Exactly 10 may use it; without the atomic script, more would slip past the check.
     */
    @Test
    void smartModelNeverOverspendsUnderConcurrency() throws Exception {
        assumeTrue(redisAvailable, "Redis not running");
        String key = newKey();
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<BudgetReservation>> results = new ArrayList<>();

        for (int i = 0; i < 50; i++) {
            results.add(pool.submit(() -> {
                startSignal.await();
                return guard.reserve(key, BUDGET, HARD_STOP, true, 100, 10);
            }));
        }
        startSignal.countDown();

        int smart = 0;
        for (Future<BudgetReservation> f : results) {
            if (f.get(10, TimeUnit.SECONDS).smartAllowed()) {
                smart++;
            }
        }
        pool.shutdown();

        assertThat(smart).isEqualTo(10);
    }
}
