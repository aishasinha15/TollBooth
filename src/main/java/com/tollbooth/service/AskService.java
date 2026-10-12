package com.tollbooth.service;

import com.tollbooth.ai.AiClient;
import com.tollbooth.ai.AiResult;
import com.tollbooth.config.TollboothProperties;
import com.tollbooth.domain.CacheResult;
import com.tollbooth.pii.MaskResult;
import com.tollbooth.pii.PiiMasker;
import com.tollbooth.ratelimit.RateLimitExceededException;
import com.tollbooth.ratelimit.RateLimitResult;
import com.tollbooth.ratelimit.TokenBucketRateLimiter;
import com.tollbooth.security.AppPrincipal;
import com.tollbooth.web.dto.AskRequest;
import com.tollbooth.web.dto.AskResponse;
import com.tollbooth.web.dto.UserTier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * The heart of Tollbooth: decides what happens to each question.
 *
 * Flow so far:
 *   rate limit (3) -> mask PII (4) -> AI call -> restore PII (4) -> cost -> record usage -> respond.
 * Still to slot in:
 *   cache lookup (6) -> budget + routing (5) before the AI call, save to cache (6) after it.
 *
 * Note: this method is NOT @Transactional. An AI call can take seconds, and
 * holding a database connection open that long would starve other requests.
 * Only the short usage insert runs in a transaction (inside UsageService).
 */
@Service
public class AskService {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);

    private final AiClient aiClient;
    private final CostCalculator costCalculator;
    private final UsageService usageService;
    private final TokenBucketRateLimiter rateLimiter;
    private final PiiMasker piiMasker;
    private final TollboothProperties props;

    public AskService(AiClient aiClient, CostCalculator costCalculator, UsageService usageService,
                      TokenBucketRateLimiter rateLimiter, PiiMasker piiMasker, TollboothProperties props) {
        this.aiClient = aiClient;
        this.costCalculator = costCalculator;
        this.usageService = usageService;
        this.rateLimiter = rateLimiter;
        this.piiMasker = piiMasker;
        this.props = props;
    }

    public AskOutcome ask(AppPrincipal app, AskRequest request) {
        long start = System.nanoTime();

        // 1. Rate limit FIRST: a rejected question costs nothing (no AI call, no DB write)
        RateLimitResult limit = checkRateLimit(app, request);

        // 2. Hide private data. Only the masked text ever leaves Tollbooth.
        MaskResult masked = piiMasker.mask(request.question());
        if (masked.hasPii()) {
            // Safe to log: placeholders and counts only, never the real values.
            log.info("Masked {} for app {}. Sent to AI: \"{}\"",
                    masked.counts(), app.appName(), masked.maskedText());
        }

        // 3. Ask the AI (with the masked question)
        String model = props.ai().defaultModel();
        String routeReason = "default model (routing comes in Phase 5)";
        AiResult result = aiClient.generate(model, masked.maskedText());

        // 4. Put the real values back so the user sees a normal answer
        String answer = masked.restore(result.text());

        // 5. Cost and the bill
        BigDecimal cost = costCalculator.cost(model, result.inputTokens(), result.outputTokens());
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        usageService.record(app.appId(), request.userId(), model,
                result.inputTokens(), result.outputTokens(), cost,
                CacheResult.MISS, routeReason, latencyMs, masked.maskedCount());

        AskResponse response = new AskResponse(answer, model, result.inputTokens(),
                result.outputTokens(), cost, latencyMs, CacheResult.MISS, masked.maskedCount());
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
