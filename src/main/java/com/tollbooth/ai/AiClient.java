package com.tollbooth.ai;

import java.util.List;

/**
 * Anything that can answer a question with an AI model.
 *
 * The rest of Tollbooth only knows this interface, never "Gemini".
 * To support GPT or Claude later, write one new class that implements it;
 * nothing else changes. (Interview term: "program to an interface".)
 */
public interface AiClient {

    /**
     * @param model  which model to use, e.g. "gemini-3.5-flash-lite"
     * @param prompt the user's question (already masked of private data)
     */
    AiResult generate(String model, String prompt);

    /**
     * Turns text into an embedding: a list of numbers that captures its meaning.
     * Questions that mean the same thing get similar numbers (used by the semantic cache).
     */
    float[] embed(String text);

    /**
     * Embeds many texts, in the same order (Phase 7: used by the threshold measurement).
     * A "default" method: implementations get this simple version for free and may
     * override it with something faster (GeminiClient sends one batch request).
     */
    default List<float[]> embedAll(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }
}
