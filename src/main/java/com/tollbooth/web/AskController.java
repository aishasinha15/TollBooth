package com.tollbooth.web;

import com.tollbooth.security.AppPrincipal;
import com.tollbooth.service.AskOutcome;
import com.tollbooth.service.AskService;
import com.tollbooth.web.dto.AskRequest;
import com.tollbooth.web.dto.AskResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /v1/ask: the one endpoint apps call with their users' questions.
 * Controllers stay thin: receive the request, hand it to the service, return the result.
 */
@RestController
@RequestMapping("/v1")
public class AskController {

    public static final String RATE_LIMIT_REMAINING_HEADER = "X-RateLimit-Remaining";

    private final AskService askService;

    public AskController(AskService askService) {
        this.askService = askService;
    }

    @Operation(summary = "Ask the AI a question on behalf of one of the app's users")
    @PostMapping("/ask")
    public ResponseEntity<AskResponse> ask(@AuthenticationPrincipal AppPrincipal app,
                                           @Valid @RequestBody AskRequest request) {
        AskOutcome outcome = askService.ask(app, request);

        // ResponseEntity lets us set headers as well as the body.
        // The header tells the app how many questions this user has left right now.
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header("X-Model", outcome.response().model());
        if (outcome.rateLimit().remaining() >= 0) {
            builder.header(RATE_LIMIT_REMAINING_HEADER, String.valueOf(outcome.rateLimit().remaining()));
        }
        return builder.body(outcome.response());
    }
}
