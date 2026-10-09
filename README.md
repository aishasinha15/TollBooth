# Tollbooth

An AI gateway for consumer apps' AI assistants. It sits between an app's chat assistant and the AI model and adds per-user rate limits, private-data masking, answer caching, difficulty-based model routing and daily budget control.

> Work in progress. Phase 1: project skeleton, database and Redis.

## Run it

Requirements: Java 21, Docker Desktop, Maven (or IntelliJ IDEA, which bundles Maven).

```bash
docker compose up -d          # starts MySQL and Redis
mvn spring-boot:run           # starts Tollbooth on http://localhost:8080
```

Check it works:

- http://localhost:8080/v1/status — shows the seeded demo app, model prices and a Redis check
- http://localhost:8080/actuator/health — database and Redis health
- http://localhost:8080/swagger-ui.html — API explorer
