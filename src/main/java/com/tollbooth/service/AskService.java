package com.tollbooth.service;

import com.tollbooth.ai.AiClient;
import com.tollbooth.ai.AiResult;
import com.tollbooth.config.TollboothProperties;
import com.tollbooth.domain.CacheResult;
import com.tollbooth.ratelimit.RateLimitExceededException;
import com.tollbooth.ratelimit.RateLimitResult;
import com.tollbooth.ratelimit.TokenBucketRateLimiter;
import com.tollbooth.security.AppPrincipal;
import com.tollbooth.web.dto.AskRequest;
import com.tollbooth.web.dto.AskResponse;
import com.tollbooth.web.dto.UserTier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * The heart of Tollbooth: decides what happens to each question.
 *
 * Flow so far:
 *   rate limit (Phase 3) -> call the AI -> calculate cost -> record usage -> respond.
 * Still to slot in:
 *   mask PII (4) -> cache lookup (6) -> budget + routing (5) -> AI -> restore PII (4) -> save to cache (6).
 *
 * Note: this method is NOT @Transactional. An AI call can take seconds, and
 * holding a database connection open that long would starve other requests.
 * Only the short usage insert runs in a transaction (inside UsageService).
 */
@Service
public class AskService {

    private final AiClient aiClient;
    private final CostCalculator costCalculator;
    private final UsageService usageService;
    private final TokenBucketRateLimiter rateLimiter;
    private final TollboothProperties props;

    public AskService(AiClient aiClient, CostCalculator costCalculator, UsageService usageService,
                      TokenBucketRateLimiter rateLimiter, TollboothProperties props) {
        this.aiClient = aiClient;
        this.costCalculator = costCalculator;
        this.usageService = usageService;
        this.rateLimiter = rateLimiter;
        this.props = props;
    }

    public AskOutcome ask(AppPrincipal app, AskRequest request) {
        long start = System.nanoTime();

        // 1. Rate limit FIRST: a rejected question costs nothing (no AI call, no DB write)
        RateLimitResult limit = checkRateLimit(app, request);

        // 2. Ask the AI
        String model = props.ai().defaultModel();
        String routeReason = "default model (routing comes in Phase 5)";
        AiResult result = aiClient.generate(model, request.question());

        // 3. Cost and the bill
        BigDecimal cost = costCalculator.cost(model, result.inputTokens(), result.outputTokens());
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        usageService.record(app.appId(), request.userId(), model,
                result.inputTokens(), result.outputTokens(), cost,
                CacheResult.MISS, routeReason, latencyMs);

        AskResponse response = new AskResponse(result.text(), model, result.inputTokens(),
                result.outputTokens(), cost, latencyMs, CacheResult.MISS);
        return new AskOutcome(response, limit);
    }

    private RateLimitResult checkRateLimit(AppPrincipal app, AskRequest request) {
        TollboothProperties.Bucket bucket = request.userTier() == UserTier.PREMIUM
                ? props.rateLimit().premium()
                : props.rateLimit().regular();

        // One bucket per user per app: user-42 in DemoShop is not user-42 in another app
        String key = "tb:bucket:" + app.appId() + ":" + request.userId();

        RateLimitResult limit = rateLimiter.tryConsume(key, bucket.capacity(), bucket.refillEvery());
        if (!limit.allowed()) {
            throw new RateLimitExceededException(limit.retryAfterMs());
        }
        return limit;
    }
}
