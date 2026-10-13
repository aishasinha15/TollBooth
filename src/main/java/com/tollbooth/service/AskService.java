package com.tollbooth.service;

import com.tollbooth.ai.AiClient;
import com.tollbooth.ai.AiProviderException;
import com.tollbooth.ai.AiResult;
import com.tollbooth.budget.BudgetExhaustedException;
import com.tollbooth.budget.BudgetGuard;
import com.tollbooth.budget.BudgetReservation;
import com.tollbooth.config.TollboothProperties;
import com.tollbooth.domain.CacheResult;
import com.tollbooth.pii.MaskResult;
import com.tollbooth.pii.PiiMasker;
import com.tollbooth.ratelimit.RateLimitExceededException;
import com.tollbooth.ratelimit.RateLimitResult;
import com.tollbooth.ratelimit.TokenBucketRateLimiter;
import com.tollbooth.routing.Difficulty;
import com.tollbooth.routing.DifficultyRouter;
import com.tollbooth.routing.RouteDecision;
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
 * Flow:
 *   1 rate limit -> 2 mask private data -> 3 route (easy/hard)
 *   -> 4 reserve budget (may downgrade to the cheap model, or stop at the hard limit)
 *   -> 5 call the AI (smart model falls back to cheap on failure)
 *   -> 6 restore private data -> 7 settle the real cost -> 8 record the bill -> respond.
 * Still to slot in: cache lookup before step 3 and cache save after step 6 (Phase 6).
 *
 * Note: this method is NOT @Transactional. An AI call can take seconds, and
 * holding a database connection open that long would starve other requests.
 */
@Service
public class AskService {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);

    private final AiClient aiClient;
    private final CostCalculator costCalculator;
    private final UsageService usageService;
    private final TokenBucketRateLimiter rateLimiter;
    private final PiiMasker piiMasker;
    private final DifficultyRouter router;
    private final BudgetGuard budgetGuard;
    private final TollboothProperties props;

    public AskService(AiClient aiClient, CostCalculator costCalculator, UsageService usageService,
                      TokenBucketRateLimiter rateLimiter, PiiMasker piiMasker, DifficultyRouter router,
                      BudgetGuard budgetGuard, TollboothProperties props) {
        this.aiClient = aiClient;
        this.costCalculator = costCalculator;
        this.usageService = usageService;
        this.rateLimiter = rateLimiter;
        this.piiMasker = piiMasker;
        this.router = router;
        this.budgetGuard = budgetGuard;
        this.props = props;
    }

    public AskOutcome ask(AppPrincipal app, AskRequest request) {
        long start = System.nanoTime();

        // 1. Rate limit FIRST: a rejected question costs nothing
        RateLimitResult limit = checkRateLimit(app, request);

        // 2. Hide private data. Only the masked text ever leaves Tollbooth.
        MaskResult masked = piiMasker.mask(request.question());
        if (masked.hasPii()) {
            log.info("Masked {} for app {}. Sent to AI: \"{}\"",
                    masked.counts(), app.appName(), masked.maskedText());
        }

        // 3. Route: is this an easy or a hard question?
        RouteDecision route = router.route(masked.maskedText());
        String cheapModel = props.ai().cheapModel();
        String smartModel = props.ai().smartModel();

        // 4. Reserve an estimate from today's budget (atomic, in Redis)
        long budgetMicros = BudgetGuard.toMicros(app.dailyBudgetUsd());
        long hardLimitMicros = budgetMicros * props.budget().hardStopPercent() / 100;
        BudgetReservation reservation = budgetGuard.reserve(
                budgetGuard.todayKey(app.appId()), budgetMicros, hardLimitMicros,
                route.difficulty() == Difficulty.HARD,
                estimateMicros(smartModel, masked.maskedText()),
                estimateMicros(cheapModel, masked.maskedText()));
        if (!reservation.allowed()) {
            throw new BudgetExhaustedException();
        }

        String model = reservation.smartAllowed() ? smartModel : cheapModel;
        String routeReason = route.reason();
        if (route.difficulty() == Difficulty.HARD && !reservation.smartAllowed()) {
            routeReason += "; budget almost used, downgraded to " + cheapModel;
        }

        // 5. Ask the AI. If the smart model fails, try the cheap one once before giving up.
        //    On any final failure, hand the reserved budget back before passing the error on.
        AiResult result;
        try {
            try {
                result = aiClient.generate(model, masked.maskedText());
            } catch (AiProviderException first) {
                if (!model.equals(smartModel)) {
                    throw first;
                }
                log.warn("{} failed ({}), falling back to {}", smartModel, first.getMessage(), cheapModel);
                model = cheapModel;
                routeReason += "; " + smartModel + " failed (" + first.getMessage() + "), fell back to " + cheapModel;
                result = aiClient.generate(model, masked.maskedText());
            }
        } catch (RuntimeException failure) {
            budgetGuard.release(reservation);
            throw failure;
        }

        // 6. Put the real values back so the user sees a normal answer
        String answer = masked.restore(result.text());

        // 7. Real cost: swap the reserved estimate for it
        BigDecimal cost = costCalculator.cost(model, result.inputTokens(), result.outputTokens());
        long spentMicros = budgetGuard.settle(reservation, BudgetGuard.toMicros(cost));
        int usedPercent = budgetMicros > 0 ? (int) (spentMicros * 100 / budgetMicros) : 0;
        boolean budgetWarning = usedPercent >= props.budget().warnAtPercent();

        // 8. The bill
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        usageService.record(app.appId(), request.userId(), model,
                result.inputTokens(), result.outputTokens(), cost,
                CacheResult.MISS, routeReason, latencyMs, masked.maskedCount());

        AskResponse response = new AskResponse(answer, model, result.inputTokens(),
                result.outputTokens(), cost, latencyMs, CacheResult.MISS, masked.maskedCount(),
                route.difficulty(), routeReason, usedPercent, budgetWarning);
        return new AskOutcome(response, limit);
    }

    /**
     * A rough cost estimate before the call: about 4 characters per token for the input,
     * plus an assumed answer length. Settled to the exact cost afterwards.
     */
    private long estimateMicros(String model, String question) {
        int inputTokens = (props.ai().systemPrompt().length() + question.length()) / 4 + 1;
        int outputTokens = props.ai().estimatedOutputTokens();
        return BudgetGuard.toMicros(costCalculator.cost(model, inputTokens, outputTokens));
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
