package com.tollbooth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Price of one AI model, in USD per 1 million tokens.
 * Used to calculate the cost of every request.
 */
@Entity
@Table(name = "model_prices")
public class ModelPrice {

    @Id
    @Column(name = "model_name")
    private String modelName;

    @Column(name = "input_price_per_million", nullable = false, precision = 12, scale = 6)
    private BigDecimal inputPricePerMillion;

    @Column(name = "output_price_per_million", nullable = false, precision = 12, scale = 6)
    private BigDecimal outputPricePerMillion;

    protected ModelPrice() {
    }

    public ModelPrice(String modelName, BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion) {
        this.modelName = modelName;
        this.inputPricePerMillion = inputPricePerMillion;
        this.outputPricePerMillion = outputPricePerMillion;
    }

    public String getModelName() { return modelName; }
    public BigDecimal getInputPricePerMillion() { return inputPricePerMillion; }
    public BigDecimal getOutputPricePerMillion() { return outputPricePerMillion; }

    public void setInputPricePerMillion(BigDecimal v) { this.inputPricePerMillion = v; }
    public void setOutputPricePerMillion(BigDecimal v) { this.outputPricePerMillion = v; }
}
