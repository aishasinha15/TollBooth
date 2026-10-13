package com.tollbooth.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tollbooth.ai.AiClient;
import com.tollbooth.ai.AiProviderException;
import com.tollbooth.ai.AiResult;
import com.tollbooth.config.TollboothProperties;
import com.tollbooth.domain.CacheResult;
import com.tollbooth.service.CostCalculator;
import com.tollbooth.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Two-level answer cache in Redis, kept separately per app.
 *
 * Level 1, EXACT: the normalized question's hash is the key.
 *   "What is your return policy ?" and "what is your return policy" share one entry.
 *   Redis key: tb:cache:exact:{appId}:{sha256}
 *
 * Level 2, SEMANTIC: same meaning, different words.
 *   Each saved question also stores its embedding. A new question is embedded and
 *   compared (cosine similarity) with the app's most recent saved questions. For the best match:
 *     similarity >= auto-accept-threshold  -> reuse straight away
 *     similarity in the grey zone          -> the cheap model answers YES/NO "same question?" first,
 *                                             but only for HARD questions (expensive answers); for EASY
 *                                             ones the check would cost more than it saves, so: miss
 *     similarity < semantic-threshold      -> miss
 *   Why the grey zone: similarity alone can't separate some pairs. In testing, "exchange policy"
 *   scored 0.925 against "return policy" (needs a different answer), while
 *   "can I send back an item" scored 0.914 (same answer). No single cut-off gets both right.
 *   Redis keys: tb:cache:sem:{appId}:{sha256}  (the entry, with its embedding)
 *               tb:cache:semidx:{appId}        (sorted set of entries, newest first)
 *
 * Scale note: comparing against up to max-semantic-entries (500) in Java is fast
 * (well under a millisecond of maths). Beyond that, a Redis vector index (HNSW)
 * would find the nearest match without checking every entry.
 *
 * Any Redis or embedding failure is treated as a miss: the cache must never break answering.
 */
@Component
public class AnswerCache {

    private static final Logger log = LoggerFactory.getLogger(AnswerCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final AiClient aiClient;
    private final CostCalculator costCalculator;
    private final TollboothProperties.Cache settings;
    private final String judgeModel;

    public AnswerCache(StringRedisTemplate redis, ObjectMapper json, AiClient aiClient,
                       CostCalculator costCalculator, TollboothProperties props) {
        this.redis = redis;
        this.json = json;
        this.aiClient = aiClient;
        this.costCalculator = costCalculator;
        this.settings = props.cache();
        this.judgeModel = props.ai().cheapModel();
    }

    // ------------------------------------------------------------------ lookup

    /** Lookup with the grey-zone check allowed. */
    public CacheLookup lookup(Long appId, String question) {
        return lookup(appId, question, true);
    }

    /**
     * @param allowCheck whether a grey-zone match may be verified with the cheap model.
     *                   The caller passes false when a fresh answer would be cheap anyway
     *                   (an EASY question): the check costs about a quarter of a short answer,
     *                   so it only pays off when the answer it might save is expensive.
     */
    public CacheLookup lookup(Long appId, String question, boolean allowCheck) {
        String normalized = QuestionNormalizer.normalize(question);
        String hash = Hashing.sha256Hex(normalized);

        // Level 1: exact match (one Redis GET, no AI call)
        CachedAnswer exact = read(exactKey(appId, hash));
        if (exact != null) {
            return new CacheLookup(CacheResult.HIT_EXACT, exact, 1.0, null, "exact match", BigDecimal.ZERO);
        }

        // Level 2: semantic match (one embedding call + comparison)
        float[] embedding = embedSafely(normalized);
        if (embedding == null) {
            return CacheLookup.miss(null);
        }
        try {
            String index = semanticIndexKey(appId);
            Set<String> recent = redis.opsForZSet().reverseRange(index, 0, settings.maxSemanticEntries() - 1);
            if (recent == null || recent.isEmpty()) {
                return CacheLookup.miss(embedding);
            }
            List<String> members = new ArrayList<>(recent);
            List<String> keys = members.stream().map(h -> semanticKey(appId, h)).toList();
            List<String> values = redis.opsForValue().multiGet(keys);

            CachedAnswer best = null;
            double bestScore = -1;
            for (int i = 0; i < members.size(); i++) {
                String value = values == null ? null : values.get(i);
                if (value == null) {
                    redis.opsForZSet().remove(index, members.get(i)); // entry expired: tidy the index
                    continue;
                }
                CachedAnswer candidate = parse(value);
                if (candidate == null || candidate.embedding() == null) {
                    continue;
                }
                double score = Vectors.cosine(embedding, Vectors.fromBase64(candidate.embedding()));
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }

            if (best == null || bestScore < settings.semanticThreshold()) {
                if (best != null) {
                    log.info("Semantic cache miss: best similarity {} to \"{}\" (below {})",
                            fmt(bestScore), best.question(), settings.semanticThreshold());
                }
                return CacheLookup.miss(embedding);
            }

            if (bestScore >= settings.autoAcceptThreshold()) {
                return new CacheLookup(CacheResult.HIT_SEMANTIC, best, bestScore, embedding,
                        "near-identical, auto-accepted", BigDecimal.ZERO);
            }

            // Grey zone, but a fresh answer is cheap: checking would cost more than it could save
            if (!allowCheck) {
                log.info("Semantic cache grey zone: similarity {} to \"{}\", check skipped (easy question)",
                        fmt(bestScore), best.question());
                return new CacheLookup(CacheResult.MISS, null, bestScore, embedding,
                        String.format("similar to \"%s\" (%s), check skipped: a fresh easy answer is cheaper",
                                best.question(), fmt(bestScore)),
                        BigDecimal.ZERO);
            }

            // Grey zone: similar topic, but is it really the same question? Ask the cheap model.
            Judgement verdict = judge(normalized, best.question());
            if (verdict.same()) {
                log.info("Semantic cache grey zone: similarity {} to \"{}\", judged SAME",
                        fmt(bestScore), best.question());
                return new CacheLookup(CacheResult.HIT_SEMANTIC, best, bestScore, embedding,
                        "confirmed by " + judgeModel, verdict.cost());
            }
            log.info("Semantic cache grey zone: similarity {} to \"{}\", judged DIFFERENT",
                    fmt(bestScore), best.question());
            return new CacheLookup(CacheResult.MISS, null, bestScore, embedding,
                    String.format("similar to \"%s\" (%s) but judged a different question by %s",
                            best.question(), fmt(bestScore), judgeModel),
                    verdict.cost());
        } catch (DataAccessException e) {
            log.warn("Semantic cache lookup failed, treating as miss: {}", e.getMessage());
            return CacheLookup.miss(embedding);
        }
    }

    // ------------------------------------------------------------------ save

    /**
     * Saves an answer at both levels. Pass the embedding from lookup() to avoid a second Gemini call.
     */
    public void save(Long appId, String question, String answer, String model, float[] embedding) {
        String normalized = QuestionNormalizer.normalize(question);
        String hash = Hashing.sha256Hex(normalized);
        Duration ttl = settings.ttl();
        try {
            write(exactKey(appId, hash), new CachedAnswer(normalized, answer, model, null), ttl);

            if (embedding == null) {
                embedding = embedSafely(normalized);
            }
            if (embedding == null) {
                return; // exact cache still works without the semantic level
            }
            write(semanticKey(appId, hash),
                    new CachedAnswer(normalized, answer, model, Vectors.toBase64(embedding)), ttl);

            String index = semanticIndexKey(appId);
            long now = System.currentTimeMillis();
            redis.opsForZSet().add(index, hash, now);
            // Drop index entries older than the TTL, then keep only the newest N
            redis.opsForZSet().removeRangeByScore(index, 0, now - ttl.toMillis());
            Long size = redis.opsForZSet().zCard(index);
            if (size != null && size > settings.maxSemanticEntries()) {
                redis.opsForZSet().removeRange(index, 0, size - settings.maxSemanticEntries() - 1);
            }
            redis.expire(index, ttl);
        } catch (DataAccessException e) {
            log.warn("Could not save to cache: {}", e.getMessage());
        }
    }

    /**
     * After a semantic hit, remember the new wording as an EXACT entry pointing to the same answer,
     * so the next person who types it gets an instant exact hit (no embedding, no YES/NO check).
     */
    public void rememberWording(Long appId, String question, CachedAnswer answer) {
        String normalized = QuestionNormalizer.normalize(question);
        try {
            write(exactKey(appId, Hashing.sha256Hex(normalized)),
                    new CachedAnswer(normalized, answer.answer(), answer.model(), null), settings.ttl());
        } catch (DataAccessException e) {
            log.warn("Could not remember wording: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    static String exactKey(Long appId, String hash) {
        return "tb:cache:exact:" + appId + ":" + hash;
    }

    static String semanticKey(Long appId, String hash) {
        return "tb:cache:sem:" + appId + ":" + hash;
    }

    static String semanticIndexKey(Long appId) {
        return "tb:cache:semidx:" + appId;
    }

    /** The grey-zone check's answer and what it cost. */
    private record Judgement(boolean same, BigDecimal cost) {
    }

    /**
     * Asks the cheap model a tiny YES/NO question (a few dozen tokens, about a tenth of a normal answer).
     * The question is phrased around the ANSWER, not the topic: "exchange policy" and "return policy"
     * share a topic but need different answers. Any failure counts as "different": never risk a wrong hit.
     */
    private Judgement judge(String newQuestion, String cachedQuestion) {
        String prompt = "Two customer questions sent to a shopping or travel app's assistant:\n"
                + "A: \"" + newQuestion + "\"\n"
                + "B: \"" + cachedQuestion + "\"\n"
                + "Would one correct answer fully and accurately answer BOTH questions? "
                + "Reply with only YES or NO.";
        try {
            AiResult result = aiClient.generate(judgeModel, prompt);
            BigDecimal cost = costCalculator.cost(judgeModel, result.inputTokens(), result.outputTokens());
            String reply = result.text() == null ? "" : result.text().trim().toUpperCase(Locale.ROOT);
            return new Judgement(reply.startsWith("YES"), cost);
        } catch (AiProviderException e) {
            log.warn("Grey-zone check failed, treating as different: {}", e.getMessage());
            return new Judgement(false, BigDecimal.ZERO);
        }
    }

    private static String fmt(double similarity) {
        return String.format(Locale.ROOT, "%.3f", similarity);
    }

    private float[] embedSafely(String text) {
        try {
            return aiClient.embed(text);
        } catch (AiProviderException e) {
            log.warn("Embedding unavailable, skipping semantic cache: {}", e.getMessage());
            return null;
        }
    }

    private CachedAnswer read(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? null : parse(value);
        } catch (DataAccessException e) {
            log.warn("Cache read failed, treating as miss: {}", e.getMessage());
            return null;
        }
    }

    private void write(String key, CachedAnswer entry, Duration ttl) {
        try {
            redis.opsForValue().set(key, json.writeValueAsString(entry), ttl);
        } catch (JsonProcessingException e) {
            log.warn("Could not serialize cache entry: {}", e.getMessage());
        }
    }

    private CachedAnswer parse(String value) {
        try {
            return json.readValue(value, CachedAnswer.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
