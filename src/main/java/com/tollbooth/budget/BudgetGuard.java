package com.tollbooth.budget;

import com.tollbooth.config.TollboothProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Keeps each app inside its daily AI budget, using a counter in Redis.
 *
 *   reserve() before the AI call: hold an estimate (atomic Lua script)
 *   settle()  after the AI call:  swap the estimate for the real cost
 *   release() if the AI call fails: give the estimate back
 *
 * Money is counted in micro-dollars (whole numbers): $0.0001453 = 145 micros (rounded up).
 */
@Component
public class BudgetGuard {

    private static final Logger log = LoggerFactory.getLogger(BudgetGuard.class);
    private static final long TWO_DAYS_SECONDS = 2 * 24 * 60 * 60;

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> RESERVE =
            RedisScript.of(new ClassPathResource("scripts/budget_reserve.lua"), List.class);

    private final StringRedisTemplate redis;
    private final ZoneId zone;

    public BudgetGuard(StringRedisTemplate redis, TollboothProperties props) {
        this.redis = redis;
        this.zone = ZoneId.of(props.budget().timezone());
    }

    /** One counter per app per day, e.g. "tb:spend:1:2026-10-12". It resets at midnight in the configured timezone. */
    public String todayKey(Long appId) {
        return "tb:spend:" + appId + ":" + LocalDate.now(zone);
    }

    public BudgetReservation reserve(String key, long budgetMicros, long hardLimitMicros,
                                     boolean wantsSmart, long smartEstimateMicros, long cheapEstimateMicros) {
        try {
            List<?> r = redis.execute(RESERVE, List.of(key),
                    String.valueOf(budgetMicros), String.valueOf(hardLimitMicros),
                    wantsSmart ? "1" : "0",
                    String.valueOf(smartEstimateMicros), String.valueOf(cheapEstimateMicros),
                    String.valueOf(TWO_DAYS_SECONDS));
            return new BudgetReservation(key, toLong(r.get(0)) == 1, toLong(r.get(1)) == 1,
                    toLong(r.get(2)), toLong(r.get(3)));
        } catch (DataAccessException e) {
            log.warn("Budget guard unavailable, allowing request untracked: {}", e.getMessage());
            return BudgetReservation.untracked(wantsSmart);
        }
    }

    /** Replace the estimate with the real cost. Returns today's total spend afterwards. */
    public long settle(BudgetReservation reservation, long actualMicros) {
        if (reservation.key() == null) {
            return 0;
        }
        try {
            Long total = redis.opsForValue().increment(reservation.key(), actualMicros - reservation.reservedMicros());
            return total == null ? 0 : total;
        } catch (DataAccessException e) {
            log.warn("Could not settle budget: {}", e.getMessage());
            return reservation.spentMicros();
        }
    }

    /** Adds a small cost that had no reservation (e.g. the cache's grey-zone check). */
    public void addSpend(String key, long micros) {
        if (micros <= 0) {
            return;
        }
        try {
            redis.opsForValue().increment(key, micros);
            redis.expire(key, java.time.Duration.ofSeconds(TWO_DAYS_SECONDS));
        } catch (DataAccessException e) {
            log.warn("Could not add spend: {}", e.getMessage());
        }
    }

    /** Today's spend so far, without reserving anything (used for cache hits). */
    public long currentSpent(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? 0 : Long.parseLong(value);
        } catch (DataAccessException | NumberFormatException e) {
            return 0;
        }
    }

    /** The AI call failed: give the reserved estimate back. */
    public void release(BudgetReservation reservation) {
        settle(reservation, 0);
    }

    /** Dollars to micro-dollars, rounded UP so we never under-count spend. */
    public static long toMicros(BigDecimal usd) {
        if (usd == null) {
            return 0;
        }
        return usd.movePointRight(6).setScale(0, RoundingMode.CEILING).longValue();
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
