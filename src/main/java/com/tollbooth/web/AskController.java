package com.tollbooth.web;

import com.tollbooth.security.AppPrincipal;
import com.tollbooth.service.AskService;
import com.tollbooth.web.dto.AskRequest;
import com.tollbooth.web.dto.AskResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
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

    private final AskService askService;

    public AskController(AskService askService) {
        this.askService = askService;
    }

    @Operation(summary = "Ask the AI a question on behalf of one of the app's users")
    @PostMapping("/ask")
    public AskResponse ask(@AuthenticationPrincipal AppPrincipal app,
                           @Valid @RequestBody AskRequest request) {
        return askService.ask(app, request);
    }
}
