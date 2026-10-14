package com.tollbooth.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tollbooth.ai.AiClient;
import com.tollbooth.cache.DuplicateModelJudge;
import com.tollbooth.cache.GeminiQuestionJudge;
import com.tollbooth.cache.QuestionNormalizer;
import com.tollbooth.cache.SameAnswerCheck;
import com.tollbooth.cache.Vectors;
import com.tollbooth.config.TollboothProperties;
import com.tollbooth.eval.CacheEvalReport.PairResult;
import com.tollbooth.eval.CacheEvalReport.PairScore;
import com.tollbooth.eval.ThresholdMaths.ScoredPair;
import com.tollbooth.routing.Difficulty;
import com.tollbooth.routing.DifficultyRouter;
import com.tollbooth.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 7: measures the semantic cache on 50 labelled question pairs instead of guessing.
 *
 *   1. Load the pairs (each labelled by a person: does ONE answer fit both questions?).
 *   2. Embed every distinct question once, normalized exactly like the real cache does it.
 *      Embeddings are kept in Redis for 7 days, so running it again costs nothing.
 *   3. Score each pair by embedding similarity: what can similarity alone separate?
 *   4. Ask the local duplicate model about all 50 pairs (one request, free).
 *   5. Optionally (runGeminiCheck=true) ask the Gemini YES/NO check too, for comparison.
 *   6. Simulate the cache's real policy with the current settings: hits, wrong hits, missed savings.
 *
 * Only exists when tollbooth.eval.enabled=true (a developer tool, not a feature for apps).
 */
@Service
@ConditionalOnProperty(prefix = "tollbooth.eval", name = "enabled", havingValue = "true")
public class CacheEvalService {

    private static final Logger log = LoggerFactory.getLogger(CacheEvalService.class);
    private static final String PAIRS_FILE = "eval/cache-pairs.json";
    private static final Duration EMBEDDING_MEMORY = Duration.ofDays(7);

    private final AiClient aiClient;
    private final DuplicateModelJudge duplicateModel;
    private final GeminiQuestionJudge gemini;
    private final DifficultyRouter router;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final TollboothProperties props;
    private final long checkDelayMs;

    public CacheEvalService(AiClient aiClient, DuplicateModelJudge duplicateModel, GeminiQuestionJudge gemini,
                            DifficultyRouter router, StringRedisTemplate redis, ObjectMapper json,
                            TollboothProperties props,
                            @Value("${tollbooth.eval.check-delay-ms:4000}") long checkDelayMs) {
        this.aiClient = aiClient;
        this.duplicateModel = duplicateModel;
        this.gemini = gemini;
        this.router = router;
        this.redis = redis;
        this.json = json;
        this.props = props;
        this.checkDelayMs = checkDelayMs;
    }

    /** Everything known about one pair while the report is built. */
    private static final class Row {
        final CachePair pair;
        final double similarity;
        Double duplicateScore;
        String geminiCheck;

        Row(CachePair pair, double similarity) {
            this.pair = pair;
            this.similarity = similarity;
        }
    }

    public CacheEvalReport run(boolean runGeminiCheck) {
        TollboothProperties.Cache settings = props.cache();
        double semantic = settings.semanticThreshold();
        double autoAccept = settings.autoAcceptThreshold();
        double duplicateThreshold = settings.duplicateThreshold();
        boolean judgeIsGemini = "gemini".equalsIgnoreCase(settings.judge());

        // 1-3. Load, embed, score by similarity
        List<CachePair> pairs = loadPairs();
        LinkedHashSet<String> texts = new LinkedHashSet<>();
        pairs.forEach(p -> {
            texts.add(QuestionNormalizer.normalize(p.a()));
            texts.add(QuestionNormalizer.normalize(p.b()));
        });
        Map<String, float[]> vectors = embedRemembering(new ArrayList<>(texts));
        List<Row> rows = new ArrayList<>();
        for (CachePair p : pairs) {
            rows.add(new Row(p, Vectors.cosine(
                    vectors.get(QuestionNormalizer.normalize(p.a())),
                    vectors.get(QuestionNormalizer.normalize(p.b())))));
        }
        rows.sort(Comparator.comparingDouble((Row r) -> r.similarity).reversed());
        List<ScoredPair> bySimilarity = rows.stream()
                .map(r -> new ScoredPair(r.pair.a(), r.pair.b(), r.pair.sameAnswer(), r.similarity)).toList();
        List<Row> reachable = rows.stream().filter(r -> r.similarity >= semantic).toList();

        // 4. The duplicate model scores all 50 pairs in one request
        String duplicateError = null;
        String duplicateModelName = null;
        long modelMs = 0;
        try {
            List<DuplicateModelJudge.Pair> request = rows.stream()
                    .map(r -> new DuplicateModelJudge.Pair(
                            QuestionNormalizer.normalize(r.pair.b()), QuestionNormalizer.normalize(r.pair.a())))
                    .toList();
            DuplicateModelJudge.ScoreResponse response = duplicateModel.score(request);
            for (int i = 0; i < rows.size(); i++) {
                rows.get(i).duplicateScore = response.scores().get(i);
            }
            duplicateModelName = response.model();
            modelMs = response.ms();
        } catch (RuntimeException e) {
            duplicateError = e.getMessage();
            log.warn("Cache eval: duplicate model unavailable: {}", e.getMessage());
        }

        // 5. Optional Gemini checks, on the pairs that would reach a judge
        CacheEvalReport.GeminiChecker geminiChecker = null;
        if (runGeminiCheck) {
            int checked = 0, correct = 0, failed = 0;
            BigDecimal cost = BigDecimal.ZERO;
            List<String> mistakes = new ArrayList<>();
            boolean first = true;
            for (Row r : reachable) {
                if (r.similarity >= autoAccept) {
                    continue;
                }
                if (!first) {
                    pause(); // stay under the free tier's requests-per-minute limit
                }
                first = false;
                SameAnswerCheck v = gemini.judge(r.pair.b(), r.pair.a());
                cost = cost.add(v.cost());
                if (v.failed()) {
                    r.geminiCheck = "FAILED";
                    failed++;
                    continue;
                }
                r.geminiCheck = v.same() ? "YES" : "NO";
                checked++;
                if (v.same() == r.pair.sameAnswer()) {
                    correct++;
                } else {
                    mistakes.add(mistake(r, "said " + r.geminiCheck));
                }
            }
            geminiChecker = new CacheEvalReport.GeminiChecker(checked, correct,
                    ThresholdMaths.percent(correct, checked), failed, mistakes, cost);
        }

        // Duplicate model summary, on the pairs it would actually judge
        CacheEvalReport.DuplicateModel duplicate;
        if (duplicateError == null) {
            List<ScoredPair> byDuplicate = reachable.stream()
                    .map(r -> new ScoredPair(r.pair.a(), r.pair.b(), r.pair.sameAnswer(), r.duplicateScore))
                    .toList();
            int correct = 0;
            List<String> mistakes = new ArrayList<>();
            for (Row r : reachable) {
                boolean same = r.duplicateScore >= duplicateThreshold;
                if (same == r.pair.sameAnswer()) {
                    correct++;
                } else {
                    mistakes.add(mistake(r, String.format(Locale.ROOT, "scored %.2f", r.duplicateScore)));
                }
            }
            duplicate = new CacheEvalReport.DuplicateModel(true, null, duplicateModelName, duplicateThreshold,
                    reachable.size(), correct, ThresholdMaths.percent(correct, reachable.size()), mistakes,
                    ThresholdMaths.highestDifferent(byDuplicate).map(CacheEvalService::score).orElse(null),
                    ThresholdMaths.lowestSame(byDuplicate).map(CacheEvalService::score).orElse(null),
                    ThresholdMaths.recommendThreshold(byDuplicate, 0.05),
                    ThresholdMaths.table(byDuplicate, ThresholdMaths.probabilityCandidates()), modelMs);
        } else {
            duplicate = new CacheEvalReport.DuplicateModel(false, duplicateError, null, duplicateThreshold,
                    0, 0, 0, List.of(), null, null, 0, List.of(), 0);
        }

        // 6. Simulate the cache with the current settings
        int correctHits = 0, wrongHits = 0, missedSavings = 0, correctMisses = 0, notChecked = 0;
        List<PairResult> results = new ArrayList<>();
        for (Row r : rows) {
            Difficulty difficulty = router.route(r.pair.b()).difficulty();
            String zone = r.similarity >= autoAccept ? "AUTO_ACCEPT" : r.similarity >= semantic ? "CHECK" : "MISS";
            Boolean hit;
            if (zone.equals("AUTO_ACCEPT")) {
                hit = true;
            } else if (zone.equals("MISS")) {
                hit = false;
            } else if (judgeIsGemini) {
                hit = difficulty == Difficulty.EASY && !settings.checkEasyQuestions()
                        ? Boolean.FALSE  // easy questions skip the paid check when check-easy-questions is false
                        : r.geminiCheck == null ? null : r.geminiCheck.equals("YES");
            } else {
                hit = r.duplicateScore == null ? null : r.duplicateScore >= duplicateThreshold;
            }
            String outcome;
            if (hit == null) {
                outcome = "NEEDS_CHECK";
                notChecked++;
            } else if (hit) {
                outcome = r.pair.sameAnswer() ? "CORRECT_HIT" : "WRONG_HIT";
                if (r.pair.sameAnswer()) correctHits++; else wrongHits++;
            } else {
                outcome = r.pair.sameAnswer() ? "MISSED_SAVING" : "CORRECT_MISS";
                if (r.pair.sameAnswer()) missedSavings++; else correctMisses++;
            }
            results.add(new PairResult(r.pair.a(), r.pair.b(), r.pair.sameAnswer() ? "SAME" : "DIFFERENT",
                    r.pair.note(), ThresholdMaths.round3(r.similarity), difficulty, zone,
                    r.duplicateScore, r.geminiCheck, outcome));
        }

        int samePairs = (int) rows.stream().filter(r -> r.pair.sameAnswer()).count();
        CacheEvalReport.Similarity similarity = new CacheEvalReport.Similarity(
                rows.size(), samePairs, rows.size() - samePairs,
                ThresholdMaths.highestDifferent(bySimilarity).map(CacheEvalService::score).orElse(null),
                ThresholdMaths.lowestSame(bySimilarity).map(CacheEvalService::score).orElse(null),
                ThresholdMaths.recommendThreshold(bySimilarity, semantic),
                (int) rows.stream().filter(r -> r.pair.sameAnswer() && r.similarity < semantic).count(),
                reachable.size());
        CacheEvalReport.Policy policy = new CacheEvalReport.Policy(semantic, autoAccept,
                judgeIsGemini ? gemini.name() : duplicateModel.name(),
                correctHits, wrongHits, missedSavings, correctMisses, notChecked);

        return new CacheEvalReport(conclusions(similarity, duplicate, geminiChecker, policy),
                similarity, duplicate, geminiChecker, policy,
                ThresholdMaths.table(bySimilarity, ThresholdMaths.candidates()), results);
    }

    private static List<String> conclusions(CacheEvalReport.Similarity s, CacheEvalReport.DuplicateModel d,
                                            CacheEvalReport.GeminiChecker g, CacheEvalReport.Policy p) {
        List<String> lines = new ArrayList<>();
        if (s.highestDifferent() != null && s.lowestSame() != null) {
            lines.add(String.format(Locale.ROOT,
                    "Similarity alone: a DIFFERENT pair scored up to %.3f (\"%s\" vs \"%s\"), a SAME pair as low as %.3f. "
                            + "The lowest auto-accept line with no wrong hits is %.2f.",
                    s.highestDifferent().score(), s.highestDifferent().a(), s.highestDifferent().b(),
                    s.lowestSame().score(), s.recommendedAutoAcceptThreshold()));
        }
        if (d.available()) {
            lines.add(String.format(Locale.ROOT,
                    "Duplicate model: right on %d of %d pairs (%.1f%%) at threshold %.2f, %d mistakes; scored 50 pairs in %d ms.",
                    d.correct(), d.pairsJudged(), d.accuracyPercent(), d.currentThreshold(), d.mistakes().size(), d.modelMs()));
            if (d.highestDifferent() != null && d.lowestSame() != null) {
                lines.add(String.format(Locale.ROOT,
                        "Duplicate model: DIFFERENT pairs scored up to %.2f, SAME pairs as low as %.2f. "
                                + "Lowest threshold with no wrong hits: %.2f.",
                        d.highestDifferent().score(), d.lowestSame().score(), d.recommendedThreshold()));
            }
        } else {
            lines.add("Duplicate model not running (it is an optional experiment): " + d.error()
                    + ". To compare it, start it with: docker compose --profile experiments up -d --build");
        }
        if (g != null) {
            lines.add(String.format(Locale.ROOT, "Gemini YES/NO check: right on %d of %d pairs (%.1f%%), %d failed to run, cost $%s.",
                    g.correct(), g.checked(), g.accuracyPercent(), g.failed(), g.costUsd().toPlainString()));
        }
        lines.add(String.format(Locale.ROOT,
                "Current settings (judge: %s): %d correct hits, %d WRONG hits, %d missed savings, %d correct misses%s.",
                p.judge(), p.correctHits(), p.wrongHits(), p.missedSavings(), p.correctMisses(),
                p.notChecked() > 0 ? ", " + p.notChecked() + " need a check that did not run" : ""));
        return lines;
    }

    private static String mistake(Row r, String verdict) {
        return String.format(Locale.ROOT, "%s for \"%s\" vs \"%s\" (labelled %s)",
                verdict, r.pair.a(), r.pair.b(), r.pair.sameAnswer() ? "SAME" : "DIFFERENT");
    }

    private static PairScore score(ScoredPair p) {
        return new PairScore(p.a(), p.b(), ThresholdMaths.round3(p.similarity()));
    }

    /** Embeds only the texts not already remembered in Redis, then remembers the new ones. */
    private Map<String, float[]> embedRemembering(List<String> texts) {
        String prefix = "tb:eval:emb:" + props.ai().embeddingModel() + ":" + props.ai().embeddingDimensions() + ":";
        Map<String, float[]> vectors = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (String text : texts) {
            String saved = readQuietly(prefix + Hashing.sha256Hex(text));
            if (saved != null) {
                vectors.put(text, Vectors.fromBase64(saved));
            } else {
                missing.add(text);
            }
        }
        log.info("Cache eval: {} questions, {} remembered, {} to embed", texts.size(), vectors.size(), missing.size());
        if (!missing.isEmpty()) {
            List<float[]> fresh = aiClient.embedAll(missing);
            for (int i = 0; i < missing.size(); i++) {
                vectors.put(missing.get(i), fresh.get(i));
                writeQuietly(prefix + Hashing.sha256Hex(missing.get(i)), Vectors.toBase64(fresh.get(i)));
            }
        }
        return vectors;
    }

    private List<CachePair> loadPairs() {
        try (InputStream in = new ClassPathResource(PAIRS_FILE).getInputStream()) {
            return json.readValue(in, new TypeReference<List<CachePair>>() { });
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + PAIRS_FILE, e);
        }
    }

    private String readQuietly(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (DataAccessException e) {
            return null;
        }
    }

    private void writeQuietly(String key, String value) {
        try {
            redis.opsForValue().set(key, value, EMBEDDING_MEMORY);
        } catch (DataAccessException e) {
            log.warn("Could not remember embedding: {}", e.getMessage());
        }
    }

    private void pause() {
        try {
            Thread.sleep(checkDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
