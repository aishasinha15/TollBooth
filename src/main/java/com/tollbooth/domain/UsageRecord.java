package com.tollbooth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row per question that passes through Tollbooth.
 * This is the permanent "bill": who asked, which model answered, tokens, cost, cache result, time taken.
 */
@Entity
@Table(name = "usage_records", indexes = {
        @Index(name = "idx_usage_app_time", columnList = "client_app_id, created_at")
})
public class UsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_app_id")
    private ClientApp clientApp;

    /** The app's own id for its end user (Tollbooth never sees names or emails here). */
    @Column(name = "user_id", nullable = false)
    private String userId;

    /** Null when the answer came from the cache (no model was called). */
    @Column(name = "model_name")
    private String modelName;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "cost_usd", nullable = false, precision = 12, scale = 8)
    private BigDecimal costUsd = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "cache_result", nullable = false, length = 16)
    private CacheResult cacheResult;

    /**
     * Why this answer happened: router score, cache notes, retries, fallbacks.
     * Phase 7: 1000 characters (the default 255 was too short once retry notes were added).
     */
    @Column(name = "route_reason", length = 1000)
    private String routeReason;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    /**
     * How many private values were hidden before the AI call (0 = none).
     * The count only, never the values. Nullable so older rows stay valid.
     */
    @Column(name = "pii_masked_count")
    private Integer piiMaskedCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected UsageRecord() {
    }

    public UsageRecord(ClientApp clientApp, String userId, String modelName,
                       int inputTokens, int outputTokens, BigDecimal costUsd,
                       CacheResult cacheResult, String routeReason, long latencyMs,
                       int piiMaskedCount) {
        this.clientApp = clientApp;
        this.userId = userId;
        this.modelName = modelName;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.costUsd = costUsd;
        this.cacheResult = cacheResult;
        this.routeReason = routeReason;
        this.latencyMs = latencyMs;
        this.piiMaskedCount = piiMaskedCount;
    }

    public Long getId() { return id; }
    public ClientApp getClientApp() { return clientApp; }
    public String getUserId() { return userId; }
    public String getModelName() { return modelName; }
    public int getInputTokens() { return inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public BigDecimal getCostUsd() { return costUsd; }
    public CacheResult getCacheResult() { return cacheResult; }
    public String getRouteReason() { return routeReason; }
    public long getLatencyMs() { return latencyMs; }
    public Integer getPiiMaskedCount() { return piiMaskedCount; }
    public Instant getCreatedAt() { return createdAt; }
}
