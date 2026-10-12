# Tollbooth

An AI gateway for consumer apps' AI assistants. It sits between an app's chat assistant and the AI model and adds per-user rate limits, private-data masking, answer caching, difficulty-based model routing and daily budget control.

> Work in progress. Done so far: project skeleton, MySQL + Redis (Phase 1), API key security and Gemini calls with usage metering (Phase 2).

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

## Notes

- API keys are stored as SHA-256 hashes, never in plain text.
- Uses Gemini's free tier, where Google may use inputs to improve its products. Send only fake test data.
