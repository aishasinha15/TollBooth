package com.tollbooth.ai;

/**
 * Thrown when the AI provider fails: bad key, rate limited, timeout, blocked answer, etc.
 * httpStatus is what Tollbooth should return to the app:
 *  - 503 = try again later (provider busy or rate-limited)
 *  - 502 = the provider gave a bad or empty answer
 *
 * retryable (Phase 7) = worth trying the SAME model again after a short wait.
 * True only for "the provider is overloaded right now" (Google 500/503).
 * Not for our quota (429: waiting 2 seconds won't refill a per-minute quota),
 * not for timeouts (we already waited up to 60 seconds), not for bad requests.
 */
public class AiProviderException extends RuntimeException {

    private final int httpStatus;
    private final boolean retryable;

    public AiProviderException(String message, int httpStatus, boolean retryable, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public AiProviderException(String message, int httpStatus, Throwable cause) {
        this(message, httpStatus, false, cause);
    }

    public AiProviderException(String message, int httpStatus) {
        this(message, httpStatus, false, null);
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
