package com.tollbooth.security;

import java.math.BigDecimal;

/**
 * "Who is calling": the client app that owns the API key on this request.
 * The filter creates it; controllers receive it with @AuthenticationPrincipal.
 * Carrying the budget here means no extra database query per request.
 */
public record AppPrincipal(Long appId, String appName, BigDecimal dailyBudgetUsd) {
}
