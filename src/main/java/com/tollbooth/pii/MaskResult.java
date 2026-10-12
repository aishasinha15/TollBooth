package com.tollbooth.pii;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * The result of masking one question.
 *
 * maskedText          = what is safe to send to the AI, e.g. "Call me on [PHONE_1]"
 * placeholderToValue  = the secret lookup table, e.g. "[PHONE_1]" -> "9876543210"
 *
 * The lookup table lives only in memory for this one request.
 * It is never saved, logged or sent anywhere.
 */
public final class MaskResult {

    private final String maskedText;
    private final Map<String, String> placeholderToValue;
    private final Map<PiiType, Integer> counts;

    MaskResult(String maskedText, Map<String, String> placeholderToValue, Map<PiiType, Integer> counts) {
        this.maskedText = maskedText;
        this.placeholderToValue = Collections.unmodifiableMap(placeholderToValue);
        this.counts = Collections.unmodifiableMap(new EnumMap<>(counts));
    }

    public String maskedText() {
        return maskedText;
    }

    public boolean hasPii() {
        return !placeholderToValue.isEmpty();
    }

    /** How many distinct private values were hidden. */
    public int maskedCount() {
        return placeholderToValue.size();
    }

    /** Safe to log: counts only, never values. E.g. {EMAIL=1, PHONE=2} */
    public Map<PiiType, Integer> counts() {
        return counts;
    }

    /**
     * Puts the real values back into the AI's answer, so the user sees a normal reply.
     * "We'll call you on [PHONE_1]" -> "We'll call you on 9876543210"
     */
    public String restore(String text) {
        if (text == null || placeholderToValue.isEmpty()) {
            return text;
        }
        String restored = text;
        for (Map.Entry<String, String> entry : placeholderToValue.entrySet()) {
            restored = restored.replace(entry.getKey(), entry.getValue());
        }
        return restored;
    }
}
