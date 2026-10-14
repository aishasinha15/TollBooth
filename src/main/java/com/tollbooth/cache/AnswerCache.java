package com.tollbooth.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tollbooth.ai.AiClient;
import com.tollbooth.ai.AiProviderException;
import com.tollbooth.config.TollboothProperties;
import com.tollbooth.domain.CacheResult;
import com.tollbooth.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Two-level answer cache in Redis, kept separately per app.
 *
 * Level 1, EXACT: the normalized question's hash is the key.
 *   "What is your return policy ?" and "what is your return policy" share one entry.
 *   Redis key: tb:cache:exact:{appId}:{sha256}
 *
 * Level 2, SAME MEANING, in two steps ("retrieve, then verify"):
 *   1. Retrieve: embed the new question and compare it (cosine similarity) with the app's most
 *      recent saved questions. Keep those scoring at least semantic-threshold, most similar first.
 *   2. Verify: a judge looks closely at the top few and decides if one really needs the same answer.
 *      The local duplicate model is free, so it checks every question. The Gemini judge costs a
 *      little, so it only checks HARD questions (whose answers are expensive enough to be worth it).
 *   Why step 2 exists: Phase 7 measured 50 labelled pairs. Questions with one detail changed
 *   ("3-day" vs "5-day" Goa trip, 0.975) scored HIGHER than real paraphrases (0.873).
 *   Similarity alone can't tell them apart, at any threshold.
 *   Redis keys: tb:cache:sem:{appId}:{sha256}  (the entry, with its embedding)
 *               tb:cache:semidx:{appId}        (sorted set of entries, newest first)
 *
 * Any Redis, embedding or judge failure is treated as a miss: the cache must never break answering.
 */
@Component
public class AnswerCache {

    private static final Logger log = LoggerFactory.getLogger(AnswerCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final AiClient aiClient;
    private final QuestionJudge judge;
    private final TollboothProperties.Cache settings;

    /** Used by Spring: picks the judge named in application.yml (tollbooth.cache.judge). */
    @Autowired
    public AnswerCache(StringRedisTemplate redis, ObjectMapper json, AiClient aiClient,
                       DuplicateModelJudge duplicateModel, GeminiQuestionJudge gemini,
                       TollboothProperties props) {
        this(redis, json, aiClient, "gemini".equalsIgnoreCase(props.cache().judge()) ? gemini : duplicateModel, props);
    }

    /** Used by tests, which pass in a fake judge. */
    AnswerCache(StringRedisTemplate redis, ObjectMapper json, AiClient aiClient,
                QuestionJudge judge, TollboothProperties props) {
        this.redis = redis;
        this.json = json;
        this.aiClient = aiClient;
        this.judge = judge;
        this.settings = props.cache();
        log.info("Answer cache judge: {}", judge.name());
    }

    /** True if checks cost nothing, so AskService can allow them for every question. */
    public boolean checksAreFree() {
        return judge.free();
    }

    // ------------------------------------------------------------------ lookup

    /** Lookup with the check allowed. */
    public CacheLookup lookup(Long appId, String question) {
        return lookup(appId, question, true);
    }

    /** A saved question and how similar it is to the new one. */
    private record Candidate(CachedAnswer answer, double similarity) {
    }

    /**
     * @param allowCheck whether a similar match may be verified by the judge. AskService passes
     *                   false for EASY questions when the judge costs money (Gemini): the check
     *                   would cost about a quarter of the cheap answer it might save.
     */
    public CacheLookup lookup(Long appId, String question, boolean allowCheck) {
        String normalized = QuestionNormalizer.normalize(question);
        String hash = Hashing.sha256Hex(normalized);

        // Level 1: exact match (one Redis GET, no AI call)
        CachedAnswer exact = read(exactKey(appId, hash));
        if (exact != null) {
            return new CacheLookup(CacheResult.HIT_EXACT, exact, 1.0, null, "exact match", BigDecimal.ZERO);
        }

        // Level 2, step 1: retrieve look-alikes with embeddings
        float[] embedding = embedSafely(normalized);
        if (embedding == null) {
            return CacheLookup.miss(null);
        }
        List<Candidate> candidates;
        try {
            candidates = similarCandidates(appId, embedding, normalized);
        } catch (DataAccessException e) {
            log.warn("Semantic cache lookup failed, treating as miss: {}", e.getMessage());
            return CacheLookup.miss(embedding);
        }
        if (candidates.isEmpty()) {
            return CacheLookup.miss(embedding);
        }
        Candidate top = candidates.get(0);

        if (top.similarity() >= settings.autoAcceptThreshold()) {
            return new CacheLookup(CacheResult.HIT_SEMANTIC, top.answer(), top.similarity(), embedding,
                    "near-identical, auto-accepted", BigDecimal.ZERO);
        }

        if (!allowCheck) {
            log.info("Semantic cache: similarity {} to \"{}\", check skipped (easy question)",
                    fmt(top.similarity()), top.answer().question());
            return new CacheLookup(CacheResult.MISS, null, top.similarity(), embedding,
                    String.format("similar to \"%s\" (%s), check skipped: a fresh easy answer is cheaper",
                            top.answer().question(), fmt(top.similarity())),
                    BigDecimal.ZERO);
        }

        // Level 2, step 2: verify. A free judge looks at the top few, a paid one only at the best.
        int k = judge.free() ? Math.max(1, settings.rerankTopK()) : 1;
        List<Candidate> shortlist = candidates.subList(0, Math.min(k, candidates.size()));
        List<SameAnswerCheck> verdicts = judge.judgeAll(normalized,
                shortlist.stream().map(c -> c.answer().question()).toList());
        BigDecimal cost = verdicts.stream().map(SameAnswerCheck::cost).reduce(BigDecimal.ZERO, BigDecimal::add);

        for (int i = 0; i < shortlist.size() && i < verdicts.size(); i++) {
            SameAnswerCheck v = verdicts.get(i);
            if (v.same()) {
                Candidate match = shortlist.get(i);
                String note = "confirmed by " + judge.name()
                        + (judge.free() ? " (score " + fmt2(v.score()) + ")" : "")
                        + (i > 0 ? ", match " + (i + 1) + " of " + shortlist.size() + " checked" : "");
                log.info("Semantic cache: similarity {} to \"{}\", {}", fmt(match.similarity()),
                        match.answer().question(), note);
                return new CacheLookup(CacheResult.HIT_SEMANTIC, match.answer(), match.similarity(), embedding,
                        note, cost);
            }
        }

        boolean allFailed = verdicts.stream().allMatch(SameAnswerCheck::failed);
        String note = allFailed
                ? String.format("similar to \"%s\" (%s) but the %s check failed, answered fresh to be safe",
                        top.answer().question(), fmt(top.similarity()), judge.name())
                : String.format("similar to \"%s\" (%s) but judged a different question by %s%s",
                        top.answer().question(), fmt(top.similarity()), judge.name(),
                        judge.free() ? " (score " + fmt2(verdicts.get(0).score()) + ")" : "");
        log.info("Semantic cache miss: {}", note);
        return new CacheLookup(CacheResult.MISS, null, top.similarity(), embedding, note, cost);
    }

    /** Saved questions at or above semantic-threshold, most similar first. */
    private List<Candidate> similarCandidates(Long appId, float[] embedding, String normalized) {
        String index = semanticIndexKey(appId);
        Set<String> recent = redis.opsForZSet().reverseRange(index, 0, settings.maxSemanticEntries() - 1);
        if (recent == null || recent.isEmpty()) {
            return List.of();
        }
        List<String> members = new ArrayList<>(recent);
        List<String> keys = members.stream().map(h -> semanticKey(appId, h)).toList();
        List<String> values = redis.opsForValue().multiGet(keys);

        List<Candidate> candidates = new ArrayList<>();
        Candidate best = null;
        for (int i = 0; i < members.size(); i++) {
            String value = values == null ? null : values.get(i);
            if (value == null) {
                redis.opsForZSet().remove(index, members.get(i)); // entry expired: tidy the index
                continue;
            }
            CachedAnswer saved = parse(value);
            if (saved == null || saved.embedding() == null) {
                continue;
            }
            Candidate c = new Candidate(saved, Vectors.cosine(embedding, Vectors.fromBase64(saved.embedding())));
            if (best == null || c.similarity() > best.similarity()) {
                best = c;
            }
            if (c.similarity() >= settings.semanticThreshold()) {
                candidates.add(c);
            }
        }
        if (candidates.isEmpty() && best != null) {
            log.info("Semantic cache miss: best similarity {} to \"{}\" (below {})",
                    fmt(best.similarity()), best.answer().question(), settings.semanticThreshold());
        }
        candidates.sort(Comparator.comparingDouble(Candidate::similarity).reversed());
        return candidates;
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
     * so the next person who types it gets an instant exact hit (no embedding, no check).
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

    private static String fmt(double similarity) {
        return String.format(Locale.ROOT, "%.3f", similarity);
    }

    private static String fmt2(double score) {
        return String.format(Locale.ROOT, "%.2f", score);
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
