package com.tollbooth.ai;

/**
 * What came back from the AI: the answer text and the token counts we are billed for.
 * outputTokens includes any hidden "thinking" tokens, because those are billed too.
 */
public record AiResult(String text, int inputTokens, int outputTokens) {
}
