package com.tollbooth.service;

import com.tollbooth.ratelimit.RateLimitResult;
import com.tollbooth.web.dto.AskResponse;

/**
 * What AskService hands back to the controller:
 * the JSON body, plus rate limit info the controller puts into response headers.
 */
public record AskOutcome(AskResponse response, RateLimitResult rateLimit) {
}
