package com.tollbooth.cache;

import java.util.Locale;

/**
 * Makes trivially different questions identical, so the EXACT cache catches them:
 *   "What is your return policy ?"   -> "what is your return policy"
 *   "what is  your RETURN policy?!"  -> "what is your return policy"
 * Anything beyond case, spacing and punctuation is left to the semantic cache.
 */
public final class QuestionNormalizer {

    private QuestionNormalizer() {
    }

    public static String normalize(String question) {
        if (question == null) {
            return "";
        }
        return question.toLowerCase(Locale.ROOT)
                .replaceAll("[?!.,;:\"'()]+", " ")  // punctuation becomes a space
                .replaceAll("\\s+", " ")             // many spaces become one
                .trim();
    }
}
