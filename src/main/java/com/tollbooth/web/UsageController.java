package com.tollbooth.web;

import com.tollbooth.security.AppPrincipal;
import com.tollbooth.service.UsageReportService;
import com.tollbooth.web.dto.DailyUsage;
import com.tollbooth.web.dto.RecentRequest;
import com.tollbooth.web.dto.UsageReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /v1/usage: the calling app's own numbers for one day (Phase 7).
 * An app only ever sees its own usage: the app comes from its API key, not from the URL.
 */
@RestController
@RequestMapping("/v1")
public class UsageController {

    private final UsageReportService reports;

    public UsageController(UsageReportService reports) {
        this.reports = reports;
    }

    @Operation(summary = "Today's usage for the calling app: questions, cache hit rate, spend, savings, fallbacks")
    @GetMapping("/usage")
    public UsageReport usage(@AuthenticationPrincipal AppPrincipal app,
                             @Parameter(description = "Day to report, e.g. 2026-10-13. Leave empty for today.")
                             @RequestParam(required = false)
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reports.report(app, date);
    }

    @Operation(summary = "Questions, cache hits and spend per day for the last N days (default 7, max 31)")
    @GetMapping("/usage/daily")
    public List<DailyUsage> daily(@AuthenticationPrincipal AppPrincipal app,
                                  @RequestParam(defaultValue = "7") int days) {
        return reports.daily(app, days);
    }

    @Operation(summary = "The newest requests (default 20, max 50): what happened, never what was asked")
    @GetMapping("/usage/recent")
    public List<RecentRequest> recent(@AuthenticationPrincipal AppPrincipal app,
                                      @RequestParam(defaultValue = "20") int limit) {
        return reports.recent(app, limit);
    }
}
