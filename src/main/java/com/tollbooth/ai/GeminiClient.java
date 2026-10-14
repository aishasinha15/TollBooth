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
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
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

    /** How many texts go in one batch embedding request. */
    private static final int EMBED_BATCH_SIZE = 50;

    private final RestClient restClient;
    private final String apiKey;
    private final String systemPrompt;
    private final String embeddingModel;
    private final int embeddingDimensions;
    private final int maxOutputTokens;

    public GeminiClient(RestClient.Builder builder, TollboothProperties props) {
        TollboothProperties.Ai ai = props.ai();
        this.apiKey = ai.apiKey();
        this.systemPrompt = ai.systemPrompt();
        this.embeddingModel = ai.embeddingModel();
        this.embeddingDimensions = ai.embeddingDimensions();
        this.maxOutputTokens = ai.maxOutputTokens();

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
        requireKey();

        // Phase 7: generationConfig.maxOutputTokens caps the answer (thinking included),
        // so one question can never cost more than the budget reserved for it.
        GeminiRequest body = new GeminiRequest(
                new Content(null, List.of(new Part(systemPrompt))),
                List.of(new Content("user", List.of(new Part(prompt)))),
                new GenerationConfig(maxOutputTokens > 0 ? maxOutputTokens : null));

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
            if (status == 404) {
                throw new AiProviderException("AI model '" + model + "' not found (check the model name in application.yml)", 502, e);
            }
            if (status == 400 || status == 403) {
                throw new AiProviderException("AI provider rejected the request (check model name and API key)", 502, e);
            }
            if (status >= 500) {
                // e.g. 503 "The model is overloaded": temporary, worth one retry (Phase 7)
                throw new AiProviderException("AI provider busy (HTTP " + status + ")", 503, true, e);
            }
            throw new AiProviderException("AI provider error (HTTP " + status + ")", 502, e);
        } catch (ResourceAccessException e) {
            // Network problem or timeout: we never got an answer
            throw new AiProviderException("AI provider did not respond in time", 503, e);
        } catch (RestClientException e) {
            // Anything else from the HTTP client, e.g. a timeout while reading the reply.
            // Without this catch-all, such errors escaped as a raw 500 and skipped the fallback.
            boolean timedOut = hasCause(e, SocketTimeoutException.class);
            log.warn("Gemini call failed: {}", e.getMessage());
            throw new AiProviderException(timedOut
                    ? "AI provider did not respond in time"
                    : "AI provider call failed", 503, e);
        }

        return toResult(response);
    }

    /**
     * One HTTP POST to {baseUrl}/models/{embeddingModel}:embedContent.
     * taskType SEMANTIC_SIMILARITY tunes the numbers for "do these two texts mean the same thing?".
     */
    @Override
    public float[] embed(String text) {
        requireKey();
        EmbedRequest body = new EmbedRequest(
                new Content(null, List.of(new Part(text))), "SEMANTIC_SIMILARITY", embeddingDimensions);
        EmbedResponse response;
        try {
            response = restClient.post()
                    .uri("/models/{model}:embedContent", embeddingModel)
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(EmbedResponse.class);
        } catch (RestClientResponseException e) {
            log.warn("Gemini embedding returned HTTP {}: {}", e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new AiProviderException("Embedding failed (HTTP " + e.getStatusCode().value() + ")", 503, e);
        } catch (RestClientException e) {
            throw new AiProviderException("Embedding failed: " + e.getMessage(), 503, e);
        }
        if (response == null) {
            throw new AiProviderException("Embedding returned no numbers", 502);
        }
        return toFloats(response.embedding());
    }

    /**
     * Phase 7: many texts in few requests, via {embeddingModel}:batchEmbedContents (up to 50 per request).
     * If the batch endpoint refuses the request, falls back to one call per text.
     */
    @Override
    public List<float[]> embedAll(List<String> texts) {
        requireKey();
        List<float[]> vectors = new ArrayList<>();
        for (int from = 0; from < texts.size(); from += EMBED_BATCH_SIZE) {
            List<String> chunk = texts.subList(from, Math.min(texts.size(), from + EMBED_BATCH_SIZE));
            vectors.addAll(embedBatch(chunk));
        }
        return vectors;
    }

    private List<float[]> embedBatch(List<String> chunk) {
        String modelPath = "models/" + embeddingModel;
        List<BatchEmbedItem> items = chunk.stream()
                .map(text -> new BatchEmbedItem(modelPath, new Content(null, List.of(new Part(text))),
                        "SEMANTIC_SIMILARITY", embeddingDimensions))
                .toList();
        BatchEmbedResponse response;
        try {
            response = restClient.post()
                    .uri("/models/{model}:batchEmbedContents", embeddingModel)
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new BatchEmbedRequest(items))
                    .retrieve()
                    .body(BatchEmbedResponse.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 429) {
                throw new AiProviderException("Embedding rate limit reached; wait a minute and try again", 503, e);
            }
            log.warn("Batch embedding returned HTTP {}, embedding one by one instead: {}",
                    status, e.getResponseBodyAsString());
            return chunk.stream().map(this::embed).toList();
        } catch (RestClientException e) {
            throw new AiProviderException("Batch embedding failed: " + e.getMessage(), 503, e);
        }
        if (response == null || response.embeddings() == null || response.embeddings().size() != chunk.size()) {
            throw new AiProviderException("Batch embedding returned the wrong number of results", 502);
        }
        return response.embeddings().stream().map(GeminiClient::toFloats).toList();
    }

    private AiResult toResult(GeminiResponse response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            throw new AiProviderException("AI provider returned no answer", 502);
        }
        Candidate first = response.candidates().get(0);
        // MAX_TOKENS = the answer hit our maxOutputTokens cap and was cut off
        boolean cutOff = "MAX_TOKENS".equals(first.finishReason());

        String text = first.content() == null || first.content().parts() == null
                ? ""
                : first.content().parts().stream()
                        .map(Part::text)
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining()); // an answer can come in several parts

        if (text.isBlank()) {
            if (cutOff) {
                // A thinking model can use the whole allowance thinking and never start the answer
                throw new AiProviderException("AI used all " + maxOutputTokens
                        + " output tokens before writing an answer (raise max-output-tokens)", 502);
            }
            // e.g. the answer was blocked (finishReason = SAFETY)
            throw new AiProviderException("AI provider returned no text (reason: " + first.finishReason() + ")", 502);
        }

        UsageMetadata usage = response.usageMetadata();
        int input = usage == null ? 0 : orZero(usage.promptTokenCount());
        // Models may "think" before answering; thinking tokens are billed as output
        int output = usage == null ? 0 : orZero(usage.candidatesTokenCount()) + orZero(usage.thoughtsTokenCount());

        return new AiResult(text, input, output, cutOff);
    }

    private void requireKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiProviderException("GEMINI_API_KEY is not set on the server", 503);
        }
    }

    private static float[] toFloats(Embedding embedding) {
        if (embedding == null || embedding.values() == null || embedding.values().isEmpty()) {
            throw new AiProviderException("Embedding returned no numbers", 502);
        }
        List<Double> values = embedding.values();
        float[] vector = new float[values.size()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = values.get(i).floatValue();
        }
        return vector;
    }

    /** Walks the "caused by" chain looking for a specific exception type. */
    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    // ---------- JSON shapes for Gemini's API (Jackson converts these to/from JSON) ----------

    /** What we send. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GeminiRequest(Content systemInstruction, List<Content> contents, GenerationConfig generationConfig) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GenerationConfig(Integer maxOutputTokens) {
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EmbedRequest(Content content, String taskType, Integer outputDimensionality) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbedResponse(Embedding embedding) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Embedding(List<Double> values) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BatchEmbedItem(String model, Content content, String taskType, Integer outputDimensionality) {
    }

    public record BatchEmbedRequest(List<BatchEmbedItem> requests) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BatchEmbedResponse(List<Embedding> embeddings) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UsageMetadata(Integer promptTokenCount, Integer candidatesTokenCount, Integer thoughtsTokenCount) {
    }
}
