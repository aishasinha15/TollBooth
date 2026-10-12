package com.tollbooth.web;

import com.tollbooth.ai.AiProviderException;
import com.tollbooth.ratelimit.RateLimitExceededException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns exceptions thrown anywhere in a controller or service into clean JSON errors.
 * Without this, the app would get Spring's generic 500 page.
 *
 * ProblemDetail is the standard JSON error format for HTTP APIs (RFC 9457):
 *   { "status": 400, "title": "Bad Request", "detail": "..." }
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** A field failed validation, e.g. question was empty. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request has invalid fields");
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));
        problem.setProperty("errors", errors);
        return problem;
    }

    /** The body wasn't valid JSON, or an enum had an unknown value like "GOLD". */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Request body is not valid JSON or has an unknown value");
    }

    /**
     * The user's token bucket is empty: 429 Too Many Requests.
     * Retry-After is the standard header telling the app how many seconds to wait.
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimit(RateLimitExceededException ex) {
        long seconds = ex.getRetryAfterSeconds();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Too many questions. Try again in " + seconds + " seconds.");
        problem.setProperty("retryAfterSeconds", seconds);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
                .header(AskController.RATE_LIMIT_REMAINING_HEADER, "0")
                .body(problem);
    }

    /** The AI provider failed (timeout, rate limit, blocked answer...). */
    @ExceptionHandler(AiProviderException.class)
    public ProblemDetail handleAiProvider(AiProviderException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(ex.getHttpStatus()), ex.getMessage());
    }
}
