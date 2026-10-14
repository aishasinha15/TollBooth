package com.tollbooth.cache;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tollbooth.config.TollboothProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 7 judge: the open-source duplicate-question model (cross-encoder/quora-distilroberta-base),
 * running in our own container (duplicate-checker/ in the project, port 8001). Free.
 *
 * Why it can be smarter than embeddings: an embedding model reads each question ALONE and
 * turns it into numbers; similar-looking questions get similar numbers even if one detail
 * changes the meaning. A cross-encoder reads BOTH questions TOGETHER, so it can notice that
 * "3-day" vs "5-day" or "Goa" vs "Manali" makes them different questions.
 * The trade-off: it must run once per pair, so it can't search 500 questions quickly.
 * That's why embeddings shortlist first and this model only checks the top few.
 *
 * If the container is down or slow, every check counts as "different" (a miss): safe, never wrong.
 */
@Component
public class DuplicateModelJudge implements QuestionJudge {

    private static final Logger log = LoggerFactory.getLogger(DuplicateModelJudge.class);

    private final RestClient restClient;
    private final double threshold;

    public DuplicateModelJudge(RestClient.Builder builder, TollboothProperties props) {
        TollboothProperties.DuplicateChecker settings = props.duplicateChecker();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(settings.timeoutMs());
        requestFactory.setReadTimeout(settings.timeoutMs());
        this.restClient = builder.baseUrl(settings.baseUrl()).requestFactory(requestFactory).build();
        this.threshold = props.cache().duplicateThreshold();
    }

    @Override
    public String name() {
        return "duplicate model";
    }

    @Override
    public boolean free() {
        return true;
    }

    @Override
    public SameAnswerCheck judge(String newQuestion, String cachedQuestion) {
        return judgeAll(newQuestion, List.of(cachedQuestion)).get(0);
    }

    /** All pairs in ONE request to the container. */
    @Override
    public List<SameAnswerCheck> judgeAll(String newQuestion, List<String> cachedQuestions) {
        List<Pair> pairs = cachedQuestions.stream()
                .map(cached -> new Pair(QuestionNormalizer.normalize(newQuestion), QuestionNormalizer.normalize(cached)))
                .toList();
        try {
            List<Double> scores = score(pairs).scores();
            List<SameAnswerCheck> verdicts = new ArrayList<>();
            for (double s : scores) {
                verdicts.add(new SameAnswerCheck(s >= threshold, false, s, BigDecimal.ZERO));
            }
            return verdicts;
        } catch (RuntimeException e) {
            log.warn("Duplicate checker unavailable, treating as different: {}", e.getMessage());
            return cachedQuestions.stream().map(c -> SameAnswerCheck.failure()).toList();
        }
    }

    /**
     * Raw scores for any pairs (also used by the Phase 7 measurement).
     * Throws if the container can't be reached or answers with the wrong number of scores.
     */
    public ScoreResponse score(List<Pair> pairs) {
        if (pairs.isEmpty()) {
            return new ScoreResponse(null, List.of(), 0);
        }
        try {
            ScoreResponse response = restClient.post()
                    .uri("/score")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ScoreRequest(pairs))
                    .retrieve()
                    .body(ScoreResponse.class);
            if (response == null || response.scores() == null || response.scores().size() != pairs.size()) {
                throw new IllegalStateException("duplicate checker returned the wrong number of scores");
            }
            return response;
        } catch (RestClientException e) {
            throw new IllegalStateException("duplicate checker not reachable (is the container running?): "
                    + e.getMessage(), e);
        }
    }

    // ---------- JSON shapes for our duplicate-checker container ----------

    public record Pair(String a, String b) {
    }

    public record ScoreRequest(List<Pair> pairs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScoreResponse(String model, List<Double> scores, long ms) {
    }
}
