package com.tollbooth.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The JSON body the app sends to POST /v1/ask.
 * The annotations are validation rules; @Valid in the controller enforces them
 * and rejects bad requests with 400 before any of our code runs.
 */
public record AskRequest(

        @Schema(example = "user-42", description = "The app's own id for its user (no names or emails)")
        @NotBlank @Size(max = 100)
        String userId,

        @Schema(example = "REGULAR")
        @NotNull
        UserTier userTier,

        @Schema(example = "GENERAL")
        @NotNull
        QuestionCategory category,

        @Schema(example = "What is your return policy?")
        @NotBlank @Size(max = 4000)
        String question
) {
}
