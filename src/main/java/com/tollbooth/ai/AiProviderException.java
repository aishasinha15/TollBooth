package com.tollbooth.ai;

/**
 * Thrown when the AI provider fails: bad key, rate limited, timeout, blocked answer, etc.
 * httpStatus is what Tollbooth should return to the app:
 *  - 503 = try again later (provider busy or rate-limited)
 *  - 502 = the provider gave a bad or empty answer
 */
public class AiProviderException extends RuntimeException {

    private final int httpStatus;

    public AiProviderException(String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    public AiProviderException(String message, int httpStatus) {
        this(message, httpStatus, null);
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
