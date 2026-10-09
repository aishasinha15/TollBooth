package com.tollbooth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A consumer app that uses Tollbooth (e.g. "DemoShop").
 * Each app has its own daily AI budget.
 */
@Entity
@Table(name = "client_apps")
public class ClientApp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "daily_budget_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal dailyBudgetUsd;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ClientApp() {
        // required by JPA
    }

    public ClientApp(String name, BigDecimal dailyBudgetUsd) {
        this.name = name;
        this.dailyBudgetUsd = dailyBudgetUsd;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public BigDecimal getDailyBudgetUsd() { return dailyBudgetUsd; }
    public Instant getCreatedAt() { return createdAt; }

    public void setDailyBudgetUsd(BigDecimal dailyBudgetUsd) { this.dailyBudgetUsd = dailyBudgetUsd; }
}
