package com.tollbooth.service;

import com.tollbooth.config.TollboothProperties;
import com.tollbooth.domain.CacheResult;
import com.tollbooth.domain.UsageRecord;
import com.tollbooth.repository.UsageRecordRepository;
import com.tollbooth.security.AppPrincipal;
import com.tollbooth.web.dto.DailyUsage;
import com.tollbooth.web.dto.RecentRequest;
import com.tollbooth.web.dto.UsageReport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Builds the Phase 7 usage report from the "bill" rows in MySQL.
 *
 * Reads one day's rows for one app and adds them up in Java. That is fine for a demo
 * (hundreds of rows). At millions of rows you would let MySQL do the adding with
 * GROUP BY queries, or keep running totals.
 */
@Service
public class UsageReportService {

    private final UsageRecordRepository usageRecords;
    private final ZoneId zone;

    public UsageReportService(UsageRecordRepository usageRecords, TollboothProperties props) {
        this.usageRecords = usageRecords;
        this.zone = ZoneId.of(props.budget().timezone());
    }

    @Transactional(readOnly = true)
    public UsageReport report(AppPrincipal app, LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now(zone);
        // "A day" in the budget's timezone: midnight to midnight in India, as Instants (UTC moments)
        Instant from = day.atStartOfDay(zone).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(zone).toInstant();
        List<UsageRecord> rows = usageRecords
                .findByClientAppIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(app.appId(), from, to);

        List<UsageRecord> aiAnswers = rows.stream().filter(r -> r.getCacheResult() == CacheResult.MISS).toList();
        List<UsageRecord> hits = rows.stream().filter(r -> r.getCacheResult() != CacheResult.MISS).toList();
        int exact = count(hits, r -> r.getCacheResult() == CacheResult.HIT_EXACT);
        int semantic = hits.size() - exact;

        BigDecimal spent = sum(rows);
        BigDecimal aiSpent = sum(aiAnswers);
        BigDecimal averageAnswer = aiAnswers.isEmpty()
                ? BigDecimal.ZERO
                : aiSpent.divide(BigDecimal.valueOf(aiAnswers.size()), 8, RoundingMode.HALF_UP);
        BigDecimal saved = averageAnswer.multiply(BigDecimal.valueOf(hits.size())).setScale(6, RoundingMode.HALF_UP);

        BigDecimal budget = app.dailyBudgetUsd() == null ? BigDecimal.ZERO : app.dailyBudgetUsd();
        double budgetPercent = budget.signum() > 0
                ? spent.multiply(BigDecimal.valueOf(100)).divide(budget, 1, RoundingMode.HALF_UP).doubleValue()
                : 0;

        return new UsageReport(
                app.appName(),
                day,
                rows.size(),
                aiAnswers.size(),
                exact,
                semantic,
                percent(hits.size(), rows.size()),
                spent.setScale(6, RoundingMode.HALF_UP),
                budget,
                budgetPercent,
                averageAnswer.setScale(6, RoundingMode.HALF_UP),
                saved,
                count(aiAnswers, r -> reasonContains(r, "retried once")),
                count(aiAnswers, r -> reasonContains(r, "fell back to")),
                count(aiAnswers, r -> reasonContains(r, "circuit open")),
                count(aiAnswers, r -> reasonContains(r, "downgraded to")),
                count(aiAnswers, r -> reasonContains(r, "answer cut at")),
                count(rows, r -> r.getPiiMaskedCount() != null && r.getPiiMaskedCount() > 0),
                averageLatency(hits),
                averageLatency(aiAnswers),
                byModel(aiAnswers));
    }

    /**
     * The last {@code days} days (today included), one entry per day even when a day had no questions.
     * One database query for the whole range, then grouped by day in the budget's timezone.
     */
    @Transactional(readOnly = true)
    public List<DailyUsage> daily(AppPrincipal app, int days) {
        int span = Math.max(1, Math.min(days, 31));
        LocalDate today = LocalDate.now(zone);
        LocalDate first = today.minusDays(span - 1L);
        List<UsageRecord> rows = usageRecords.findByClientAppIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                app.appId(), first.atStartOfDay(zone).toInstant(), today.plusDays(1).atStartOfDay(zone).toInstant());

        Map<LocalDate, List<UsageRecord>> byDay = rows.stream()
                .collect(Collectors.groupingBy(r -> LocalDate.ofInstant(r.getCreatedAt(), zone)));
        List<DailyUsage> result = new ArrayList<>();
        for (LocalDate day = first; !day.isAfter(today); day = day.plusDays(1)) {
            List<UsageRecord> dayRows = byDay.getOrDefault(day, List.of());
            int ai = count(dayRows, r -> r.getCacheResult() == CacheResult.MISS);
            result.add(new DailyUsage(day, dayRows.size(), ai, dayRows.size() - ai,
                    sum(dayRows).setScale(6, RoundingMode.HALF_UP)));
        }
        return result;
    }

    /** The newest requests (at most 50), without any question text. */
    @Transactional(readOnly = true)
    public List<RecentRequest> recent(AppPrincipal app, int limit) {
        int max = Math.max(1, Math.min(limit, 50));
        return usageRecords.findTop50ByClientAppIdOrderByIdDesc(app.appId()).stream()
                .limit(max)
                .map(r -> new RecentRequest(r.getCreatedAt(), r.getUserId(), r.getCacheResult(), r.getModelName(),
                        r.getCostUsd(), r.getLatencyMs(),
                        r.getPiiMaskedCount() == null ? 0 : r.getPiiMaskedCount(), r.getRouteReason()))
                .toList();
    }

    private static List<UsageReport.ModelUsage> byModel(List<UsageRecord> aiAnswers) {
        // TreeMap keeps the models in alphabetical order, so the report is stable
        Map<String, List<UsageRecord>> grouped = aiAnswers.stream()
                .filter(r -> r.getModelName() != null)
                .collect(Collectors.groupingBy(UsageRecord::getModelName, TreeMap::new, Collectors.toList()));
        return grouped.entrySet().stream()
                .map(e -> new UsageReport.ModelUsage(
                        e.getKey(),
                        e.getValue().size(),
                        e.getValue().stream().mapToLong(UsageRecord::getInputTokens).sum(),
                        e.getValue().stream().mapToLong(UsageRecord::getOutputTokens).sum(),
                        sum(e.getValue()).setScale(6, RoundingMode.HALF_UP)))
                .toList();
    }

    private static BigDecimal sum(List<UsageRecord> rows) {
        return rows.stream()
                .map(UsageRecord::getCostUsd)
                .filter(c -> c != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static int count(List<UsageRecord> rows, Predicate<UsageRecord> test) {
        return (int) rows.stream().filter(test).count();
    }

    private static boolean reasonContains(UsageRecord r, String text) {
        return r.getRouteReason() != null && r.getRouteReason().contains(text);
    }

    private static double percent(int part, int whole) {
        return whole == 0 ? 0 : Math.round(part * 1000.0 / whole) / 10.0; // one decimal place
    }

    private static long averageLatency(List<UsageRecord> rows) {
        return Math.round(rows.stream().mapToLong(UsageRecord::getLatencyMs).average().orElse(0));
    }
}
