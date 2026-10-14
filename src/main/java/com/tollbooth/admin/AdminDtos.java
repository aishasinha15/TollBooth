package com.tollbooth.admin;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/** Request and response shapes for the admin API (records grouped in one file). */
public final class AdminDtos {

    private AdminDtos() {
    }

    public record CreateAppRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull @DecimalMin("0.01") @DecimalMax("10000") BigDecimal dailyBudgetUsd) {
    }

    public record UpdateBudgetRequest(
            @NotNull @DecimalMin("0.01") @DecimalMax("10000") BigDecimal dailyBudgetUsd) {
    }

    /** The only response that ever contains a full key. */
    public record NewKey(Long appId, String appName, BigDecimal dailyBudgetUsd,
                         Long keyId, String apiKey, String note) {
    }

    public record AppSummary(Long id, String name, BigDecimal dailyBudgetUsd, Instant createdAt, long activeKeys) {
    }

    public record KeySummary(Long id, String keyPrefix, boolean active, Instant createdAt) {
    }
}
