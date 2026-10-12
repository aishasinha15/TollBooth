package com.tollbooth.service;

import com.tollbooth.domain.ModelPrice;
import com.tollbooth.repository.ModelPriceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Turns token counts into money:
 *   cost = inputTokens  x inputPrice  / 1,000,000
 *        + outputTokens x outputPrice / 1,000,000
 *
 * Uses BigDecimal, never double: double can't store values like 0.1 exactly,
 * and tiny rounding errors add up across millions of requests.
 */
@Service
public class CostCalculator {

    private static final Logger log = LoggerFactory.getLogger(CostCalculator.class);
    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    private final ModelPriceRepository prices;

    public CostCalculator(ModelPriceRepository prices) {
        this.prices = prices;
    }

    public BigDecimal cost(String model, int inputTokens, int outputTokens) {
        Optional<ModelPrice> price = prices.findById(model);
        if (price.isEmpty()) {
            log.warn("No price configured for model '{}'; recording cost as 0", model);
            return BigDecimal.ZERO;
        }
        BigDecimal inputCost = price.get().getInputPricePerMillion().multiply(BigDecimal.valueOf(inputTokens));
        BigDecimal outputCost = price.get().getOutputPricePerMillion().multiply(BigDecimal.valueOf(outputTokens));
        return inputCost.add(outputCost).divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
    }
}
