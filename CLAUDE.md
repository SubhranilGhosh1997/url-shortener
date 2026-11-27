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
- Unit and web-slice tests (`*Test`) need no running services. `UrlShortnerApplicationTests` is a `@SpringBootTest` context-load test that needs live Postgres, Redis and Kafka on `localhost`, so run the rest with `./mvnw test -Dtest="*Test,!UrlShortnerApplicationTests" -Dsurefire.failIfNoSpecifiedTests=false`. `security/SecurityRulesTest` (`@WebMvcTest` + `jwt()`) is the place to pin down who may call what.

## Architecture

The flow spans `UrlController`, `UrlService`, `ClickEventProducer` and `ClickEventConsumer`:

- **Shorten** (`POST /api/shorten`): `UrlNormalizer` first validates the URL (absolute http/https only, otherwise `InvalidUrlException` → 400) and puts it in canonical form. It lowercases the scheme and host and drops default ports and a bare `/` root, but deliberately leaves paths, query strings and fragments alone. If the caller already shortened that normalized URL, their oldest row's code is returned; reuse is per user, so two users get separate links (an index on `(user_id, original_url)` keeps the check fast; it isn't unique, because two simultaneous requests for a new URL may both insert it). Otherwise the service saves a `UrlMapping` twice. The first save gets the IDENTITY id. `ShortCodeGenerator` turns that id into a 7-character Base62 `shortCode` (a 4-round Feistel permutation over 42 bits, cycle-walked below 62^7), and the second save stores it. The scramble is one-to-one, so never change its round keys: that would change future codes and could clash with stored ones. The mapping is then cached in Redis under `url:{code}`. Codes are unique by construction, so nothing checks for collisions.
- **Redirect** (`GET /{code}`): cache-aside. The service reads Redis `url:{code}` and, on a miss, falls back to `repository.findByShortCode` and repopulates the cache. Then `recordClick` publishes a `ClickEvent` to the Kafka topic `url-click-events` (keyed by short code) and returns a `302`.
- **Count clicks**: `ClickEventConsumer` (group `analytics-group`) calls `ClickCounter.increment`, which runs Redis `INCR clicks:{code}`; `ClickCounter` is the only place that knows the key format. `ClickEventProducer.publish` runs on the request thread and must never block or throw: `max.block.ms` is 500 ms (the Kafka default of 60 s would hang redirects whenever Kafka is down), and any failure, whether synchronous or in the send future, falls back to `ClickCounter.increment` directly. `KafkaCircuitBreaker` (closed, then open for 30 s after a failure, then half-open with one trial click) keeps redirects from paying even the 500 ms on every click during an outage. A timed-out acknowledgement can therefore double-count a click, which is accepted over losing it. Keep the producer timeouts in both `application*.yml` files. Listeners use `spring.kafka.listener.auto-startup: false` and are started by `config/KafkaListenerStarter` after `ApplicationReadyEvent`, retrying every 10 s. With auto-startup on, an unresolvable broker host (e.g. a stopped Kafka container) makes the consumer constructor throw and the whole app fail to boot. Click counts live only in Redis. They are not persisted to Postgres, so they are lost if Redis is flushed.
- **Analytics** (`GET /api/analytics/{code}`): reads the caller's own mapping from Postgres and the counter from Redis.
- **Delete** (`DELETE /api/urls/{code}`): removes the caller's row plus the `url:{code}` and `clicks:{code}` Redis keys. Codes are never reissued, because ids are never reused.
- **List** (`GET /api/urls?page&size`): newest first, returned as `PagedResponse<UrlSummary>` (our own DTO, not Spring's `Page`). Page and size are clamped (size ≤ 100), not rejected, and the click counts for a page come from one Redis `MGET`. Only the caller's links are returned.
- API docs: springdoc (`springdoc-openapi-starter-webmvc-ui` 3.x, the line that matches Spring Boot 4) serves Swagger UI at `/swagger-ui.html` and the spec at `/v3/api-docs`. Metadata lives in `config/OpenApiConfig` and in the `@Operation`/`@ApiResponse` annotations on `UrlController`. `GET /{code}` is `@Hidden` from Swagger, because a fetch that follows a cross-origin 302 always fails CORS. `GET /api/preview/{code}` is the Swagger-friendly equivalent, and it does not record a click. The catch-all `GET /{code}` must never shadow single-segment paths such as `/swagger-ui.html`. Literal mappings currently win over it, so keep any new root-level endpoints literal.
- Errors: `ResourceNotFoundException` is mapped to a 404 `ErrorResponse` by `GlobalExceptionHandler`.

### Accounts and security

- **Public:** `POST /api/auth/register|login`, `GET /{code}` (redirect), `GET /api/preview/{code}` and the Swagger/OpenAPI paths. **Everything else under `/api` needs `Authorization: Bearer <JWT>`.** Unlisted paths are denied. The rules live in `security/SecurityConfig`; a new public endpoint must be added there explicitly, and in Swagger marked with an empty `@SecurityRequirements`.
- **Ownership:** `url_mapping.user_id` comes only from the token (`CurrentUser.id(jwt)` reads the `sub` claim). Owner-scoped operations (list, analytics, delete) go through `UrlService.findOwned`/user-filtered queries and answer **404, not 403**, for someone else's link, so links can't be probed. Never take a user id from a request body or parameter.
- **Tokens:** RS256, signed with the private key and verified with the public key (`security/JwtConfig`, Spring's resource server + Nimbus; no third-party JWT library). Claims: `iss` (checked), `sub` = user id, `email`, `iat`, `exp` (1 h, `app.jwt.ttl`). Keys come from `APP_JWT_PRIVATE_KEY`/`APP_JWT_PUBLIC_KEY` (PEM; whitespace and `\n` escapes are ignored). With neither set, a temporary key pair is generated and every restart invalidates all tokens. Never commit keys: `.env` and `*.pem` are git-ignored.
- **The `auth` package is a self-contained module, kept ready to become its own `user-service`:** it owns `users` (V2), its entity, its exception handler and token issuing. URL code must never import from `auth`; it only sees the user id via `security/CurrentUser`. That's also why `url_mapping.user_id` has **no foreign key** to `users` (see V3). Moving `auth` out means: take the package and `V2`, and give this service only the public key.
- Passwords: BCrypt; 8–72 chars (BCrypt ignores bytes past 72); emails are trimmed and lowercased. Login gives the same 401 for an unknown email and a wrong password, and checks a dummy hash for unknown emails so response times don't reveal which accounts exist.

Cache entries have no TTL. If you add expiry or custom codes (both on the README roadmap), update the Redis keys and the Postgres entity together.

## Configuration and profiles

- `application.yml` (default) points at `localhost` for Postgres, Redis and Kafka, with `app.base-url: http://localhost:8080/`.
- `application-docker.yml` is activated by `SPRING_PROFILES_ACTIVE=docker` in compose. It uses container hostnames (`url-shortener-postgres`, `my-redis`, `url-shortner-kafka-1`) and `app.base-url: http://localhost:8081/`. Changes to shared settings must be made in both files.
- The schema is owned by **Flyway**: versioned scripts in `src/main/resources/db/migration` (`V1__create_url_mapping.sql`, ...) run at startup before Hibernate, and each is recorded in `flyway_schema_history`. Hibernate runs with `ddl-auto: validate`, so the app refuses to start if an entity and the database disagree. **Every schema change, including indexes and constraints, goes in a new `V{n+1}__description.sql`. Never edit a script that has already run (its checksum is verified), and don't put indexes or constraints in entity annotations.** A data fix that existing rows need (e.g. backfilling a new column) also belongs in a migration.
- Kafka advertises `PLAINTEXT://url-shortner-kafka-1:9092`, so an app running on the host (outside compose) can reach the broker's bootstrap but not its advertised address. To run the app locally against the compose Kafka, either add a hosts-file entry for that name or change the advertised listener.
- Kafka uses Spring's `JsonSerializer`/`JsonDeserializer` with `trusted.packages: "*"`. Renaming or moving `model.ClickEvent` changes the type header and can break consumption of events already in the topic.
