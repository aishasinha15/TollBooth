package com.tollbooth.service;

import com.tollbooth.domain.ModelPrice;
import com.tollbooth.repository.ModelPriceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * A unit test: tests CostCalculator alone, with a FAKE repository (a "mock").
 * No database, no Docker, runs in milliseconds.
 */
@ExtendWith(MockitoExtension.class)
class CostCalculatorTest {

    @Mock
    ModelPriceRepository prices;

    @Test
    void calculatesCostFromTokenCounts() {
        // $0.10 per million input tokens, $0.40 per million output tokens
        when(prices.findById("test-model")).thenReturn(Optional.of(
                new ModelPrice("test-model", new BigDecimal("0.10"), new BigDecimal("0.40"))));
        CostCalculator calculator = new CostCalculator(prices);

        // 1,000,000 input tokens = $0.10, 500,000 output tokens = $0.20, total $0.30
        BigDecimal cost = calculator.cost("test-model", 1_000_000, 500_000);

        assertThat(cost).isEqualByComparingTo("0.30");
    }

    @Test
    void smallRequestsKeepTinyCostsExact() {
        when(prices.findById("test-model")).thenReturn(Optional.of(
                new ModelPrice("test-model", new BigDecimal("0.10"), new BigDecimal("0.40"))));
        CostCalculator calculator = new CostCalculator(prices);

        // 20 input tokens = $0.000002, 100 output tokens = $0.00004
        assertThat(calculator.cost("test-model", 20, 100)).isEqualByComparingTo("0.000042");
    }

    @Test
    void unknownModelCostsZero() {
        when(prices.findById("mystery-model")).thenReturn(Optional.empty());
        CostCalculator calculator = new CostCalculator(prices);

        assertThat(calculator.cost("mystery-model", 100, 100)).isEqualByComparingTo("0");
    }
}
