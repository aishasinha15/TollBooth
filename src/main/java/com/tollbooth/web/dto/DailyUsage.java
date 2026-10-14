package com.tollbooth.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One day in the dashboard's "last N days" chart. */
public record DailyUsage(LocalDate date, int questions, int answeredByAi, int cacheHits, BigDecimal spentUsd) {
}
