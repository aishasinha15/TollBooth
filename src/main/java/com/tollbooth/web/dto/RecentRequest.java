package com.tollbooth.web.dto;

import com.tollbooth.domain.CacheResult;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of the dashboard's "recent requests" list.
 * There is no question text here: Tollbooth never stores what users asked, only what happened.
 */
public record RecentRequest(Instant time, String userId, CacheResult cacheResult, String model,
                            BigDecimal costUsd, long latencyMs, int privateValuesMasked, String routeReason) {
}
