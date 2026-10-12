package com.tollbooth.service;

import com.tollbooth.ai.AiClient;
import com.tollbooth.ai.AiResult;
import com.tollbooth.config.TollboothProperties;
import com.tollbooth.domain.CacheResult;
import com.tollbooth.security.AppPrincipal;
import com.tollbooth.web.dto.AskRequest;
import com.tollbooth.web.dto.AskResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * The heart of Tollbooth: decides what happens to each question.
 *
 * Phase 2 flow:  call the AI -> calculate cost -> record usage -> respond.
 * Later phases slot in here, in this order:
 *   rate limit (3) -> mask PII (4) -> cache lookup (6) -> budget + routing (5)
 *   -> AI call -> restore PII (4) -> save to cache (6) -> record usage.
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
    private final TollboothProperties props;

    public AskService(AiClient aiClient, CostCalculator costCalculator,
                      UsageService usageService, TollboothProperties props) {
        this.aiClient = aiClient;
        this.costCalculator = costCalculator;
        this.usageService = usageService;
        this.props = props;
    }

    public AskResponse ask(AppPrincipal app, AskRequest request) {
        long start = System.nanoTime();

        String model = props.ai().defaultModel();
        String routeReason = "default model (routing comes in Phase 5)";

        AiResult result = aiClient.generate(model, request.question());

        BigDecimal cost = costCalculator.cost(model, result.inputTokens(), result.outputTokens());
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        usageService.record(app.appId(), request.userId(), model,
                result.inputTokens(), result.outputTokens(), cost,
                CacheResult.MISS, routeReason, latencyMs);

        return new AskResponse(result.text(), model, result.inputTokens(), result.outputTokens(),
                cost, latencyMs, CacheResult.MISS);
    }
}
