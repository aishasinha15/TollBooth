package com.tollbooth.cache;

/**
 * One saved answer, stored in Redis as JSON.
 *
 * @param question  the normalized question it answers (for logs and debugging)
 * @param answer    the answer text
 * @param model     the model that originally produced it
 * @param embedding the question's meaning as Base64 numbers (only for semantic entries; null otherwise)
 */
public record CachedAnswer(String question, String answer, String model, String embedding) {
}
