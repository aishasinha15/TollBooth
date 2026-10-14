package com.tollbooth.eval;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /v1/eval/cache-threshold: runs the Phase 7 cache measurement.
 * POST, not GET, because it costs money (embeddings and optional YES/NO checks).
 * Only exists when tollbooth.eval.enabled=true; switch it off in production.
 */
@RestController
@RequestMapping("/v1/eval")
@ConditionalOnProperty(prefix = "tollbooth.eval", name = "enabled", havingValue = "true")
public class EvalController {

    private final CacheEvalService evalService;

    public EvalController(CacheEvalService evalService) {
        this.evalService = evalService;
    }

    @Operation(summary = "Developer tool: measure the semantic cache on 50 labelled question pairs")
    @PostMapping("/cache-threshold")
    public CacheEvalReport cacheThreshold(
            @Parameter(description = "Also ask the Gemini YES/NO check about every pair that reaches a judge, "
                    + "to compare it with the duplicate model (slower: about 4 s per pair)")
            @RequestParam(defaultValue = "false") boolean runGeminiCheck) {
        return evalService.run(runGeminiCheck);
    }
}
