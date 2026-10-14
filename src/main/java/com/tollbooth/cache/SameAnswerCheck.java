package com.tollbooth.cache;

import java.math.BigDecimal;

/**
 * One judge's verdict on "would one answer fully answer both questions?"
 *
 * @param same   true only if the judge is confident they need the same answer
 * @param failed the check could not run (container down, AI busy...); counts as "different"
 * @param score  how sure the judge is, 0 to 1 (duplicate model: its score; Gemini: 1 for YES, 0 for NO)
 * @param cost   what the check cost in USD (0 for the local duplicate model)
 */
public record SameAnswerCheck(boolean same, boolean failed, double score, BigDecimal cost) {

    public static SameAnswerCheck failure() {
        return new SameAnswerCheck(false, true, 0, BigDecimal.ZERO);
    }
}
