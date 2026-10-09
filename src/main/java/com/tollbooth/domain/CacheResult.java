package com.tollbooth.domain;

/** How a request was answered: from the exact cache, the semantic cache, or fresh from the AI. */
public enum CacheResult {
    HIT_EXACT,
    HIT_SEMANTIC,
    MISS
}
