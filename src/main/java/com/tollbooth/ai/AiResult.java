package com.tollbooth.ai;

/**
 * What came back from the AI: the answer text and the token counts we are billed for.
 * outputTokens includes any hidden "thinking" tokens, because those are billed too.
 *
 * truncated (Phase 7) = the answer stopped because it hit max-output-tokens,
 * so the text may end mid-sentence. Such answers are never cached.
 */
public record AiResult(String text, int inputTokens, int outputTokens, boolean truncated) {

    /** A complete (not cut off) answer. Records can have extra constructors like this one. */
    public AiResult(String text, int inputTokens, int outputTokens) {
        this(text, inputTokens, outputTokens, false);
    }
}
