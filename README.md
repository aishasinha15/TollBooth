# Tollbooth

An AI gateway for consumer apps' AI assistants. It sits between an app's chat assistant and the AI model and adds per-user rate limits, private-data masking, answer caching, difficulty-based model routing and daily budget control.

> Work in progress. Done so far: project skeleton with MySQL and Redis (Phase 1), API key security and Gemini calls with usage metering (Phase 2), per-user token bucket rate limiting (Phase 3), private-data masking (Phase 4), model routing and daily budget (Phase 5).

## Run it

Requirements: Java 21, Docker Desktop, Maven (or IntelliJ IDEA, which bundles Maven), and a free Gemini API key from [Google AI Studio](https://aistudio.google.com).

```bash
docker compose up -d                 # starts MySQL and Redis
export GEMINI_API_KEY=your-key-here  # never commit this
mvn spring-boot:run                  # starts Tollbooth on http://localhost:8080
```

## Try it

Open http://localhost:8080/swagger-ui.html, click **Authorize**, enter the demo key `tb_demo_key_change_me`, then try `POST /v1/ask`.

Or with curl:

```bash
curl -X POST http://localhost:8080/v1/ask \
  -H "X-Api-Key: tb_demo_key_change_me" \
  -H "Content-Type: application/json" \
  -d '{"userId":"user-42","userTier":"REGULAR","category":"GENERAL","question":"What is a return policy?"}'
```

## How it works

- **API keys** are stored as SHA-256 hashes, never in plain text.
- **Rate limiting** uses a token bucket per user, implemented as a Lua script that runs atomically inside Redis. Under 100 simultaneous requests against a bucket of 20, exactly 20 pass. Rejected requests get `429` with a `Retry-After` header; every response carries `X-RateLimit-Remaining`. If Redis is unreachable, requests are allowed (fail open).
- **Private-data masking** replaces emails, Indian mobile numbers and card numbers (Luhn-checked) with placeholders like `[PHONE_1]` before anything reaches the AI, then restores the real values in the answer. The lookup table lives only in memory for that one request.
- **Model routing** sends easy questions to a cheap model and hard ones (planning, comparing, several conditions) to a smarter model, using simple scored rules. If the smart model fails, the request falls back to the cheap one.
- **Daily budget** per app: an estimate is reserved atomically in Redis before each AI call and settled to the real cost afterwards. At 80% responses carry a warning, at 100% everything goes to the cheap model, and at 120% AI calls stop until midnight.
- **Usage metering** records model, tokens, cost and latency for every answered question in MySQL.

## Notes

- Uses Gemini's free tier, where Google may use inputs to improve its products. Send only fake test data.
- `TokenBucketRateLimiterTest` runs against the Redis from `docker compose`; it is skipped if Redis isn't running.
