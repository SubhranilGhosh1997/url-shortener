# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

A URL shortener REST API with click analytics. It uses Java 21, Spring Boot 4.1 (Maven), PostgreSQL, Redis, Kafka and Lombok. The base package is `com.subhranil.url_shortner`. The spelling "shortner" in the package and artifact names is intentional, so keep it.

## Commands

Use the Maven wrapper (`mvnw.cmd` on Windows, `./mvnw` in Git Bash):

```bash
./mvnw clean package -DskipTests      # build target/url-shortner-0.0.1-SNAPSHOT.jar
docker-compose up --build             # start Postgres, Redis, Kafka and the app (app on host port 8081)
docker-compose down                   # stop; add -v to also wipe the Postgres volume
./mvnw test                           # run all tests
./mvnw test -Dtest=UrlShortnerApplicationTests            # one test class
./mvnw test -Dtest=UrlShortnerApplicationTests#contextLoads  # one test method
./mvnw spring-boot:run                # run locally with the default profile (port 8080)
```

- The `Dockerfile` copies the prebuilt jar from `target/`. Run `mvnw package` before `docker-compose up --build`, or the image ships stale code.
- The only test is a `@SpringBootTest` context-load test with no mocks or Testcontainers. It needs live Postgres, Redis and Kafka on `localhost`, which is why the README builds with `-DskipTests`.

## Architecture

The flow spans `UrlController`, `UrlService`, `ClickEventProducer` and `ClickEventConsumer`:

- **Shorten** (`POST /api/shorten`): the service saves a `UrlMapping` twice. The first save gets the IDENTITY id. `ShortCodeGenerator` turns that id into a 7-character Base62 `shortCode` (a 4-round Feistel permutation over 42 bits, cycle-walked below 62^7), and the second save stores it. The scramble is one-to-one, so never change its round keys: that would change future codes and could clash with stored ones. Codes created before this scheme (`1`–`5`) and by an earlier version of it are still stored as-is and keep working, because lookups go by the stored `short_code`. The mapping is then cached in Redis under `url:{code}`. Codes are unique by construction, so nothing checks for collisions.
- **Redirect** (`GET /{code}`): cache-aside. The service reads Redis `url:{code}` and, on a miss, falls back to `repository.findByShortCode` and repopulates the cache. Then `recordClick` publishes a `ClickEvent` to the Kafka topic `url-click-events` (keyed by short code) and returns a `302`.
- **Count clicks**: `ClickEventConsumer` (group `analytics-group`) runs Redis `INCR clicks:{code}`. Click counts live only in Redis. They are not persisted to Postgres, so they are lost if Redis is flushed.
- **Analytics** (`GET /api/analytics/{code}`): reads the mapping from Postgres and the counter from Redis.
- **List** (`GET /api/urls?page&size`): newest first, returned as `PagedResponse<UrlSummary>` (our own DTO, not Spring's `Page`). Page and size are clamped (size ≤ 100), not rejected, and the click counts for a page come from one Redis `MGET`. It is public on purpose for now: user accounts are planned, and this endpoint should then be scoped to the signed-in user's links.
- API docs: springdoc (`springdoc-openapi-starter-webmvc-ui` 3.x, the line that matches Spring Boot 4) serves Swagger UI at `/swagger-ui.html` and the spec at `/v3/api-docs`. Metadata lives in `config/OpenApiConfig` and in the `@Operation`/`@ApiResponse` annotations on `UrlController`. `GET /{code}` is `@Hidden` from Swagger, because a fetch that follows a cross-origin 302 always fails CORS. `GET /api/preview/{code}` is the Swagger-friendly equivalent, and it does not record a click. The catch-all `GET /{code}` must never shadow single-segment paths such as `/swagger-ui.html`. Literal mappings currently win over it, so keep any new root-level endpoints literal.
- Errors: `ResourceNotFoundException` is mapped to a 404 `ErrorResponse` by `GlobalExceptionHandler`.

Cache entries have no TTL. If you add expiry or custom codes (both on the README roadmap), update the Redis keys and the Postgres entity together.

## Configuration and profiles

- `application.yml` (default) points at `localhost` for Postgres, Redis and Kafka, with `app.base-url: http://localhost:8080/`.
- `application-docker.yml` is activated by `SPRING_PROFILES_ACTIVE=docker` in compose. It uses container hostnames (`url-shortener-postgres`, `my-redis`, `url-shortner-kafka-1`) and `app.base-url: http://localhost:8081/`. Changes to shared settings must be made in both files.
- The schema is managed by Hibernate `ddl-auto: update`. There is no migration tool.
- Kafka advertises `PLAINTEXT://url-shortner-kafka-1:9092`, so an app running on the host (outside compose) can reach the broker's bootstrap but not its advertised address. To run the app locally against the compose Kafka, either add a hosts-file entry for that name or change the advertised listener.
- Kafka uses Spring's `JsonSerializer`/`JsonDeserializer` with `trusted.packages: "*"`. Renaming or moving `model.ClickEvent` changes the type header and can break consumption of events already in the topic.
