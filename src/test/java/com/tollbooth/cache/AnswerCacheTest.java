package com.tollbooth.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tollbooth.ai.AiClient;
import com.tollbooth.ai.AiProviderException;
import com.tollbooth.ai.AiResult;
import com.tollbooth.config.TollboothProperties;
import com.tollbooth.domain.CacheResult;
import com.tollbooth.repository.ModelPriceRepository;
import com.tollbooth.service.CostCalculator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * INTEGRATION test against the real Redis from docker-compose (skipped if it isn't running).
 *
 * Uses a FAKE AiClient: "embeddings" are hand-made 3-number vectors, so we control exactly how
 * similar questions are, and the grey-zone YES/NO check answers from a fixed list.
 * No Gemini calls, no cost. Each test uses a random app id, so tests never share entries.
 *
 * Thresholds: below 0.88 miss, 0.88 to 0.97 grey zone (checked), 0.97 and up auto-accepted.
 */
class AnswerCacheTest {

    private static final Map<String, float[]> FAKE_EMBEDDINGS = Map.of(
            "what is your return policy", new float[]{1.0f, 0.10f, 0.0f},
            "can i send back shoes that don t fit", new float[]{0.98f, 0.15f, 0.02f}, // 0.998: auto-accept
            "tell me your return policy", new float[]{1.0f, 0.10f, 0.38f},             // 0.935: grey zone, same
            "what is your exchange policy", new float[]{1.0f, 0.10f, 0.40f},           // 0.929: grey zone, different
            "how do i cancel my order", new float[]{0.2f, 1.0f, 0.0f},                 // 0.29: miss
            "what are your delivery charges", new float[]{0.0f, 0.0f, 1.0f});

    /** Pairs the fake checker says need the same answer. Everything else: NO. */
    private static final Set<String> SAME_ANSWER_PAIRS = Set.of(
            "tell me your return policy|what is your return policy");

    private static final AtomicInteger checksRun = new AtomicInteger();

    private static final AiClient FAKE_AI = new AiClient() {
        @Override
        public AiResult generate(String model, String prompt) {
            checksRun.incrementAndGet();
            String a = between(prompt, "A: \"", "\"");
            String b = between(prompt, "B: \"", "\"");
            boolean same = SAME_ANSWER_PAIRS.contains(a + "|" + b) || SAME_ANSWER_PAIRS.contains(b + "|" + a);
            return new AiResult(same ? "YES" : "NO", 40, 1);
        }

        @Override
        public float[] embed(String text) {
            float[] v = FAKE_EMBEDDINGS.get(text);
            if (v == null) {
                throw new AiProviderException("no fake embedding for: " + text, 503);
            }
            return v;
        }
    };

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start) + start.length();
        return text.substring(from, text.indexOf(end, from));
    }

    private static LettuceConnectionFactory connectionFactory;
    private static boolean redisAvailable;
    private static AnswerCache cache;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        try {
            template.hasKey("tb:test:ping");
            redisAvailable = true;
        } catch (Exception e) {
            redisAvailable = false;
        }
        TollboothProperties.Ai ai = new TollboothProperties.Ai(
                null, null, "cheap-model", "smart-model", null, 0, 0, 0, null, 0);
        TollboothProperties.Cache settings = new TollboothProperties.Cache(
                true, Duration.ofMinutes(5), 0.88, 0.97, 500);
        TollboothProperties props = new TollboothProperties(ai, null, null, settings, null, null);
        // A mocked price list returns no prices, so check costs are 0 here (cost maths has its own tests)
        CostCalculator costs = new CostCalculator(mock(ModelPriceRepository.class));
        cache = new AnswerCache(template, new ObjectMapper(), FAKE_AI, costs, props);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    private static Long newAppId() {
        return -ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }

    private static Long appWithReturnPolicy() {
        Long app = newAppId();
        cache.save(app, "What is your return policy?", "30 days.", "cheap-model", null);
        return app;
    }

    @Test
    void emptyCacheIsAMiss() {
        assumeTrue(redisAvailable, "Redis not running; start it with docker compose up -d");
        assertThat(cache.lookup(newAppId(), "What is your return policy?").result()).isEqualTo(CacheResult.MISS);
    }

    @Test
    void sameQuestionDifferentPunctuationIsExactHit() {
        assumeTrue(redisAvailable, "Redis not running");
        CacheLookup hit = cache.lookup(appWithReturnPolicy(), "what is your RETURN policy ?");

        assertThat(hit.result()).isEqualTo(CacheResult.HIT_EXACT);
        assertThat(hit.answer().answer()).isEqualTo("30 days.");
    }

    @Test
    void nearIdenticalMeaningIsAutoAcceptedWithoutACheck() {
        assumeTrue(redisAvailable, "Redis not running");
        Long app = appWithReturnPolicy();
        int checksBefore = checksRun.get();

        CacheLookup hit = cache.lookup(app, "Can I send back shoes that don't fit?");

        assertThat(hit.result()).isEqualTo(CacheResult.HIT_SEMANTIC);
        assertThat(hit.similarity()).isGreaterThanOrEqualTo(0.97);
        assertThat(checksRun.get()).isEqualTo(checksBefore); // no YES/NO call needed
    }

    @Test
    void greyZoneSameQuestionIsConfirmedAndReused() {
        assumeTrue(redisAvailable, "Redis not running");
        CacheLookup hit = cache.lookup(appWithReturnPolicy(), "Tell me your return policy");

        assertThat(hit.result()).isEqualTo(CacheResult.HIT_SEMANTIC);
        assertThat(hit.similarity()).isBetween(0.88, 0.97);
        assertThat(hit.note()).startsWith("confirmed by");
        assertThat(hit.answer().answer()).isEqualTo("30 days.");
    }

    /** The real bug found in testing: "exchange policy" must NOT get the returns answer. */
    @Test
    void greyZoneDifferentQuestionIsRejected() {
        assumeTrue(redisAvailable, "Redis not running");
        CacheLookup result = cache.lookup(appWithReturnPolicy(), "What is your exchange policy?");

        assertThat(result.result()).isEqualTo(CacheResult.MISS);
        assertThat(result.similarity()).isBetween(0.88, 0.97);
        assertThat(result.note()).contains("judged a different question");
    }

    /** EASY questions skip the check: a fresh cheap answer costs about as much as checking. */
    @Test
    void greyZoneWithoutCheckAllowedIsAMissAndCostsNothing() {
        assumeTrue(redisAvailable, "Redis not running");
        Long app = appWithReturnPolicy();
        int checksBefore = checksRun.get();

        CacheLookup result = cache.lookup(app, "Tell me your return policy", false);

        assertThat(result.result()).isEqualTo(CacheResult.MISS);
        assertThat(result.note()).contains("check skipped");
        assertThat(checksRun.get()).isEqualTo(checksBefore);
    }

    @Test
    void clearlyDifferentMeaningIsAMissWithoutACheck() {
        assumeTrue(redisAvailable, "Redis not running");
        Long app = appWithReturnPolicy();
        int checksBefore = checksRun.get();

        CacheLookup result = cache.lookup(app, "How do I cancel my order?");

        assertThat(result.result()).isEqualTo(CacheResult.MISS);
        assertThat(result.embedding()).isNotNull(); // kept so saving needs no second embedding call
        assertThat(checksRun.get()).isEqualTo(checksBefore);
    }

    @Test
    void rememberedWordingBecomesAnExactHit() {
        assumeTrue(redisAvailable, "Redis not running");
        Long app = appWithReturnPolicy();
        CacheLookup first = cache.lookup(app, "Tell me your return policy");
        cache.rememberWording(app, "Tell me your return policy", first.answer());

        assertThat(cache.lookup(app, "tell me your return policy!").result()).isEqualTo(CacheResult.HIT_EXACT);
    }

    @Test
    void appsNeverSeeEachOthersAnswers() {
        assumeTrue(redisAvailable, "Redis not running");
        appWithReturnPolicy();
        assertThat(cache.lookup(newAppId(), "What is your return policy?").result()).isEqualTo(CacheResult.MISS);
    }

    @Test
    void picksTheClosestOfSeveralEntries() {
        assumeTrue(redisAvailable, "Redis not running");
        Long app = newAppId();
        cache.save(app, "What are your delivery charges?", "Free over Rs 499.", "cheap-model", null);
        cache.save(app, "What is your return policy?", "30 days.", "cheap-model", null);
        cache.save(app, "How do I cancel my order?", "Go to My Orders.", "cheap-model", null);

        CacheLookup hit = cache.lookup(app, "Can I send back shoes that don't fit?");

        assertThat(hit.answer().answer()).isEqualTo("30 days.");
    }

    @Test
    void embeddingFailureStillAllowsExactCache() {
        assumeTrue(redisAvailable, "Redis not running");
        Long app = newAppId();
        cache.save(app, "Do you ship to Nepal?", "Yes.", "cheap-model", null);

        assertThat(cache.lookup(app, "do you ship to nepal").result()).isEqualTo(CacheResult.HIT_EXACT);
        assertThat(cache.lookup(app, "Do you deliver in Nepal?").result()).isEqualTo(CacheResult.MISS);
    }
}
