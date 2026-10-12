package com.tollbooth.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.tollbooth.config.TollboothProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Talks to Google's Gemini REST API.
 *
 * One call = one HTTP POST to:
 *   {baseUrl}/models/{model}:generateContent
 * with the API key in the "x-goog-api-key" header.
 */
@Component
public class GeminiClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private final RestClient restClient;
    private final String apiKey;
    private final String systemPrompt;

    public GeminiClient(RestClient.Builder builder, TollboothProperties props) {
        TollboothProperties.Ai ai = props.ai();
        this.apiKey = ai.apiKey();
        this.systemPrompt = ai.systemPrompt();

        // Timeouts: never let a slow AI call hang a request forever
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(ai.connectTimeoutMs());
        requestFactory.setReadTimeout(ai.readTimeoutMs());

        this.restClient = builder
                .baseUrl(ai.baseUrl())
                .requestFactory(requestFactory)
                .build();

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("GEMINI_API_KEY is not set. /v1/ask will fail until you set it.");
        }
    }

    @Override
    public AiResult generate(String model, String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiProviderException("GEMINI_API_KEY is not set on the server", 503);
        }

        GeminiRequest body = new GeminiRequest(
                new Content(null, List.of(new Part(systemPrompt))),
                List.of(new Content("user", List.of(new Part(prompt)))));

        GeminiResponse response;
        try {
            response = restClient.post()
                    .uri("/models/{model}:generateContent", model)
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(GeminiResponse.class);
        } catch (RestClientResponseException e) {
            // Gemini answered with an error status (4xx/5xx)
            int status = e.getStatusCode().value();
            log.warn("Gemini returned HTTP {}: {}", status, e.getResponseBodyAsString());
            if (status == 429) {
                throw new AiProviderException("AI provider rate limit reached, try again shortly", 503, e);
            }
            if (status == 400 || status == 403) {
                throw new AiProviderException("AI provider rejected the request (check model name and API key)", 502, e);
            }
            throw new AiProviderException("AI provider error (HTTP " + status + ")", 502, e);
        } catch (ResourceAccessException e) {
            // Network problem or timeout: we never got an answer
            throw new AiProviderException("AI provider did not respond in time", 503, e);
        }

        return toResult(response);
    }

    private AiResult toResult(GeminiResponse response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            throw new AiProviderException("AI provider returned no answer", 502);
        }
        Candidate first = response.candidates().get(0);
        if (first.content() == null || first.content().parts() == null) {
            // Happens when the answer is blocked (finishReason = SAFETY, etc.)
            throw new AiProviderException("AI provider returned no text (reason: " + first.finishReason() + ")", 502);
        }

        // An answer can come in several parts; join them into one string
        String text = first.content().parts().stream()
                .map(Part::text)
                .filter(Objects::nonNull)
                .collect(Collectors.joining());

        UsageMetadata usage = response.usageMetadata();
        int input = usage == null ? 0 : orZero(usage.promptTokenCount());
        // Gemini 2.5 models may "think" before answering; thinking tokens are billed as output
        int output = usage == null ? 0 : orZero(usage.candidatesTokenCount()) + orZero(usage.thoughtsTokenCount());

        return new AiResult(text, input, output);
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    // ---------- JSON shapes for Gemini's API (Jackson converts these to/from JSON) ----------

    /** What we send. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GeminiRequest(Content systemInstruction, List<Content> contents) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(String role, List<Part> parts) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Part(String text) {
    }

    /** What we get back (we ignore fields we don't need). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GeminiResponse(List<Candidate> candidates, UsageMetadata usageMetadata) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(Content content, String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UsageMetadata(Integer promptTokenCount, Integer candidatesTokenCount, Integer thoughtsTokenCount) {
    }
}
