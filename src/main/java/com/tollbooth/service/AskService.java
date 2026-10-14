package com.tollbooth.service;

import com.tollbooth.ai.AiResult;
import com.tollbooth.ai.ResilientAiCaller;
import com.tollbooth.budget.BudgetExhaustedException;
import com.tollbooth.budget.BudgetGuard;
import com.tollbooth.budget.BudgetReservation;
import com.tollbooth.cache.AnswerCache;
import com.tollbooth.cache.CacheLookup;
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
import com.tollbooth.web.dto.QuestionCategory;
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
 *   -> 4 cache lookup (exact, then semantic): on a hit, answer instantly for free
 *   -> 5 reserve the worst-case cost (may downgrade to the cheap model, or stop at the hard limit)
 *   -> 6 call the AI (a busy model is retried once; the smart model falls back to cheap on failure)
 *   -> 7 restore private data -> 8 save to cache -> 9 settle the real cost -> 10 record the bill.
 *
 * Note: this method is NOT @Transactional. An AI call can take seconds, and
 * holding a database connection open that long would starve other requests.
 */
@Service
public class AskService {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);

    private final ResilientAiCaller aiCaller;
    private final CostCalculator costCalculator;
    private final UsageService usageService;
    private final TokenBucketRateLimiter rateLimiter;
    private final PiiMasker piiMasker;
    private final DifficultyRouter router;
    private final BudgetGuard budgetGuard;
    private final AnswerCache answerCache;
    private final TollboothProperties props;

    public AskService(ResilientAiCaller aiCaller, CostCalculator costCalculator, UsageService usageService,
                      TokenBucketRateLimiter rateLimiter, PiiMasker piiMasker, DifficultyRouter router,
                      BudgetGuard budgetGuard, AnswerCache answerCache, TollboothProperties props) {
        this.aiCaller = aiCaller;
        this.costCalculator = costCalculator;
        this.usageService = usageService;
        this.rateLimiter = rateLimiter;
        this.piiMasker = piiMasker;
        this.router = router;
        this.budgetGuard = budgetGuard;
        this.answerCache = answerCache;
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

        long budgetMicros = BudgetGuard.toMicros(app.dailyBudgetUsd());
        String budgetKey = budgetGuard.todayKey(app.appId());

        // 4. Cache: only GENERAL questions with no private data, so no user ever sees another's answer
        boolean cacheable = props.cache().enabled()
                && request.category() == QuestionCategory.GENERAL
                && !masked.hasPii();
        // The careful same-answer check. Phase 6 limited the paid Gemini check to HARD questions
        // (an estimate said it wasn't worth it for cheap answers). Phase 7 measured it: about 6 in 10 checks
        // say YES and a check costs about 1/40 of a real cheap answer, so by default every question is checked.
        boolean allowCheck = props.cache().checkEasyQuestions()
                || answerCache.checksAreFree()
                || route.difficulty() == Difficulty.HARD;
        CacheLookup cached = cacheable
                ? answerCache.lookup(app.appId(), masked.maskedText(), allowCheck)
                : CacheLookup.miss(null);
        if (cached.isHit()) {
            if (cached.result() == CacheResult.HIT_SEMANTIC) {
                answerCache.rememberWording(app.appId(), masked.maskedText(), cached.answer());
            }
            return answerFromCache(app, request, cached, route, budgetMicros, budgetKey, limit, start);
        }

        // 5. Reserve the WORST case from today's budget (atomic, in Redis).
        //    Phase 7: the answer length is capped, so the real cost can only be lower.
        long hardLimitMicros = budgetMicros * props.budget().hardStopPercent() / 100;
        BudgetReservation reservation = budgetGuard.reserve(
                budgetKey, budgetMicros, hardLimitMicros,
                route.difficulty() == Difficulty.HARD,
                worstCaseMicros(smartModel, masked.maskedText()),
                worstCaseMicros(cheapModel, masked.maskedText()));
        if (!reservation.allowed()) {
            throw new BudgetExhaustedException();
        }

        String model = reservation.smartAllowed() ? smartModel : cheapModel;
        String routeReason = route.reason();
        if (cached.note() != null) {
            routeReason += "; cache: " + cached.note();
        }
        if (route.difficulty() == Difficulty.HARD && !reservation.smartAllowed()) {
            routeReason += "; budget almost used, downgraded to " + cheapModel;
        }

        // 6. Ask the AI. A busy model gets one retry (Phase 7); if the smart model still fails,
        //    the cheap one answers instead (Phase 5).
        //    On any final failure, hand the reserved budget back before passing the error on.
        ResilientAiCaller.Outcome call;
        try {
            String fallback = model.equals(smartModel) ? cheapModel : null;
            call = aiCaller.call(model, fallback, masked.maskedText());
        } catch (RuntimeException failure) {
            budgetGuard.release(reservation);
            throw failure;
        }
        AiResult result = call.result();
        model = call.model();
        if (!call.notes().isEmpty()) {
            routeReason += "; " + call.notes();
        }
        if (result.truncated()) {
            routeReason += "; answer cut at " + props.ai().maxOutputTokens() + " tokens";
        }

        // 7. Put the real values back so the user sees a normal answer
        String answer = masked.restore(result.text());

        // 8. Save for next time (reusing the embedding computed during lookup).
        //    A cut-off answer is never cached: nobody else should get half an answer.
        if (cacheable && !result.truncated()) {
            answerCache.save(app.appId(), masked.maskedText(), answer, model, cached.embedding());
        }

        // 9. Real cost: swap the reserved estimate for it
        // (includes the grey-zone cache check, if one ran before this miss)
        BigDecimal cost = costCalculator.cost(model, result.inputTokens(), result.outputTokens())
                .add(cached.checkCost());
        long spentMicros = budgetGuard.settle(reservation, BudgetGuard.toMicros(cost));
        int usedPercent = percent(spentMicros, budgetMicros);
        boolean budgetWarning = usedPercent >= props.budget().warnAtPercent();

        // 10. The bill
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
     * A cache hit: no answer generated, no budget reservation. Free, except a grey-zone hit,
     * which cost one tiny YES/NO check. Still recorded in the bill,
     * so savings can be measured (count of HIT rows x what they would have cost).
     */
    private AskOutcome answerFromCache(AppPrincipal app, AskRequest request, CacheLookup cached,
                                       RouteDecision route, long budgetMicros, String budgetKey,
                                       RateLimitResult limit, long start) {
        String reason = cached.result() == CacheResult.HIT_EXACT
                ? "cache hit (exact match)"
                : String.format("cache hit (semantic, similarity %.3f to \"%s\", %s)",
                        cached.similarity(), cached.answer().question(), cached.note());
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        // A confirmed grey-zone hit cost a tiny YES/NO check; everything else is free
        BigDecimal cost = cached.checkCost();
        String checkModel = cost.signum() > 0 ? props.ai().cheapModel() : null;
        if (cost.signum() > 0) {
            budgetGuard.addSpend(budgetKey, BudgetGuard.toMicros(cost));
        }
        usageService.record(app.appId(), request.userId(), checkModel, 0, 0, cost,
                cached.result(), reason, latencyMs, 0);

        int usedPercent = percent(budgetGuard.currentSpent(budgetKey), budgetMicros);
        AskResponse response = new AskResponse(cached.answer().answer(), cached.answer().model(), 0, 0,
                cost, latencyMs, cached.result(), 0, route.difficulty(), reason,
                usedPercent, usedPercent >= props.budget().warnAtPercent());
        return new AskOutcome(response, limit);
    }

    private static int percent(long spentMicros, long budgetMicros) {
        return budgetMicros > 0 ? (int) (spentMicros * 100 / budgetMicros) : 0;
    }

    /**
     * The most this call can cost (Phase 7). Input: one token per character, a generous upper bound
     * (real text averages about 4 characters per token). Output: the longest answer allowed.
     * Settled to the exact cost afterwards, which can only bring the total DOWN.
     */
    private long worstCaseMicros(String model, String question) {
        int inputTokens = props.ai().systemPrompt().length() + question.length();
        int outputTokens = props.ai().maxOutputTokens();
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
