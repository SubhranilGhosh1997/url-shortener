# URL Shortener

A URL shortener REST API with user accounts and click analytics, built with **Java 21**, **Spring Boot 4**, **Spring Security (JWT)**, **PostgreSQL**, **Redis** and **Apache Kafka**, and fully containerized with **Docker Compose**.

Redirects are served from a Redis cache. Click tracking runs asynchronously through Kafka, so the redirect never waits on analytics processing, and keeps working even when Kafka is down.

---

## Features

- **Accounts and JWT login:** sign up with email and password and get a stateless RS256 access token. Every short link belongs to its creator.
- **Short, random-looking codes** such as `tJvSh8k`, which don't reveal how many links exist.
- **One link per URL per user:** shortening the same URL again returns your existing link. URLs are normalized first, so `https://github.com` and `HTTPS://GitHub.com:443/` count as the same.
- **Manage your links:** list them (paginated, with click counts), view analytics for each, and delete them.
- **Fast public redirects** from a Redis cache, with clicks counted asynchronously through Kafka.
- **Resilient to Kafka outages:** a 500 ms cap, a Redis fallback and a circuit breaker keep redirects fast and clicks counted. The app even starts without Kafka and reconnects on its own.
- **Versioned database schema** with Flyway migrations.
- **Interactive API docs** in Swagger UI, with an **Authorize** button for your token.
- **63 automated tests**, covering the security rules, token signing and verification, short-code generation, URL normalization and the Kafka fallback.

---

## Tech stack

| Layer | Technology | Why it's here |
|---|---|---|
| API | Java 21, Spring Boot | REST endpoints, business logic |
| Security | Spring Security, JWT (RS256), BCrypt | Accounts and stateless login; every link has an owner |
| Database | PostgreSQL 16 + Flyway | Permanent source of truth for URL mappings; schema changes are versioned SQL migrations |
| Cache + counters | Redis 7 | Fast redirect lookups (cache-aside) and atomic click counters (`INCR`) |
| Messaging | Apache Kafka | Decouples the redirect from click analytics processing |
| Packaging | Docker, Docker Compose | One command starts the whole stack |

---

## Architecture

```
   Client ── POST /api/auth/register|login ─▶ ┌──────────────────────┐
          ◀── JWT (RS256, 1 h) ────────────── │                      │──users──▶ PostgreSQL
                                              │   Spring Boot app    │           (schema by Flyway)
   Client ── POST /api/shorten  + JWT ──────▶ │                      │──links──▶ PostgreSQL
                                              │  Spring Security     │──cache──▶ Redis  url:{code}
                                              │  checks every token  │
   Client ── GET /{code}  (public) ─────────▶ │  1. Check Redis      │
          ◀── 302 redirect ────────────────── │  2. Fallback to DB   │
                                              │  3. Publish click    │──event──▶ Kafka "url-click-events"
                                              └──────────────────────┘    │            │
                                                 Kafka down? count the    │            ▼
                                                 click in Redis directly ─┘   ┌──────────────────────┐
                                                 (circuit breaker)            │ Kafka consumer       │
                                                                              │ (analytics-group)    │
                                                                              │ INCR clicks:{code} ──┼─▶ Redis
                                                                              └──────────────────────┘

   Client ── GET /api/urls | /api/analytics/{code} | DELETE /api/urls/{code}  + JWT
             → only the caller's own links (others get 404); click counts read from Redis
```

### How it works

1. **Sign up / log in:** the password is checked against its BCrypt hash, and the app returns a signed JWT whose subject is the user ID. Every protected call is verified against the public key, without any database lookup.
2. **Shorten:** the URL is validated and normalized. If you shortened it before, your existing code is returned. Otherwise it is saved to PostgreSQL with you as owner, the generated row ID is scrambled into a random-looking 7-character Base62 short code (for example `tJvSh8k`), and the mapping is written to Redis.
3. **Redirect:** public, no token. The app looks in Redis first (cache-aside). On a miss it reads PostgreSQL and repopulates the cache. It then publishes a click event to Kafka and returns an HTTP `302`.
4. **Count clicks:** a Kafka consumer reads each event and runs an atomic `INCR` on a Redis counter for that short code. If Kafka is unavailable, the click is counted in Redis directly.
5. **Manage:** listing, analytics and deleting only ever touch the caller's own links. Deleting also removes the link's cache entry and click counter.

### Design decisions

- **Stateless JWT with RS256:** tokens are signed with a private key and verified with the public key, so no session storage is needed and any instance can check a token. Accounts live in a self-contained `auth` module that the rest of the code never imports, so it could move into its own user service; the link service would only need the public key.
- **Ownership comes only from the token:** the user ID is never read from the request body. Someone else's link answers `404` rather than `403`, so links can't be probed.
- **Flyway for the schema:** every schema change is a versioned SQL migration, and Hibernate only validates the entities against it (`ddl-auto: validate`), so the app refuses to start if code and database disagree.
- **Cache-aside with Redis:** the redirect is the hottest path. Serving it from memory keeps PostgreSQL out of the common case.
- **Kafka for analytics:** counting a click is a side effect, not part of the redirect. Publishing an event lets the two run independently. If Kafka is unavailable, publishing gives up after 500 ms and the click is counted directly in Redis instead. A circuit breaker then skips Kafka for 30 seconds at a time, so during an outage redirects stay as fast as normal. The app also starts and runs without Kafka, and reconnects by itself when Kafka is back.
- **Redis `INCR` for counters:** the increment is atomic, so concurrent clicks can't corrupt the count.
- **Scrambled IDs as short codes:** the database ID goes through a one-to-one scramble (a Feistel permutation) and is Base62-encoded to 7 characters. Codes look random and don't reveal how many links exist, but they are still unique by construction, so there are no collision checks.
- **Conservative URL normalization:** only changes that can't alter the page are made (lowercase scheme and host, default ports, a bare trailing `/`). Paths, query strings and fragments are kept exactly, because `/docs` and `/docs/` can be different pages.

---

## Getting started

### Prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (running)
- JDK 21 (used once to build the jar; the Maven wrapper is included)

### Run it

```bash
# 1. Clone
git clone https://github.com/SubhranilGhosh1997/url-shortener.git
cd url-shortener

# 2. Build the jar
./mvnw clean package -DskipTests        # Windows: mvnw.cmd clean package -DskipTests

# 3. Start everything (PostgreSQL, Redis, Kafka, app)
docker-compose up --build
```

Wait until the logs show the app has started and the Kafka consumer has joined the `analytics-group`. The first start takes a minute or two because images are downloaded. The database schema is created automatically by Flyway on first start.

Then open **[http://localhost:8081/swagger-ui.html](http://localhost:8081/swagger-ui.html)**:

1. Run `POST /api/auth/register` with an email and a password (8+ characters), and copy `accessToken` from the response.
2. Click **Authorize** at the top, paste the token, and confirm.
3. Try `POST /api/shorten`, then open the returned `shortUrl` in a new tab and check `GET /api/urls` for the click count.

### Services and ports

| Service | Container | Host port |
|---|---|---|
| Spring Boot app | `url-shortener-app` | **8081** |
| PostgreSQL | `url-shortener-postgres` | 5432 |
| Redis | `my-redis` | 6379 |
| Kafka | `url-shortner-kafka-1` | 9092 |

### JWT signing keys

Out of the box, the app generates a temporary RSA key pair at startup and logs a warning. That's fine for local development, but every restart logs everyone out. To keep tokens valid across restarts, create a key pair once and put it in a `.env` file next to `docker-compose.yml` (`.env` and `*.pem` are git-ignored, so never commit them):

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem
openssl pkey -in jwt-private.pem -pubout -out jwt-public.pem

# .env: one line per key with the line breaks removed (the app ignores whitespace in the PEM)
printf 'APP_JWT_PRIVATE_KEY=%s\n' "$(tr -d '\r\n' < jwt-private.pem)" >  .env
printf 'APP_JWT_PUBLIC_KEY=%s\n'  "$(tr -d '\r\n' < jwt-public.pem)"  >> .env
```

### Stop it

```bash
docker-compose down        # stop containers, keep PostgreSQL data
docker-compose down -v     # stop containers and delete the PostgreSQL volume
```

### Running tests

The unit and web-layer tests need no running services:

```bash
./mvnw test -Dtest="*Test,!UrlShortnerApplicationTests" -Dsurefire.failIfNoSpecifiedTests=false
```

| Test class | What it checks |
|---|---|
| `SecurityRulesTest` | Which endpoints are public, `401` without a valid token, user ID taken from the token, `404` for other users' links |
| `JwtRoundTripTest` | Tokens verify with the right key; forged, expired and wrong-issuer tokens are rejected; PEM keys load |
| `AuthServiceTest` | Registration, duplicate emails, password hashing, identical errors for unknown email and wrong password |
| `ShortCodeGeneratorTest` | 7-character codes, no duplicates across a million IDs, no visible pattern |
| `UrlNormalizerTest` | Which URL variants count as the same link, and which inputs are rejected |
| `ClickEventProducerTest`, `KafkaCircuitBreakerTest`, `KafkaListenerStarterTest` | The Kafka fallback, the circuit breaker and the background consumer start |

`UrlShortnerApplicationTests` starts the full application and needs PostgreSQL, Redis and Kafka on `localhost`, so it is excluded above.

---

## API

Base URL: `http://localhost:8081`

**Swagger UI:** [http://localhost:8081/swagger-ui.html](http://localhost:8081/swagger-ui.html) (OpenAPI JSON at `/v3/api-docs`). Register or log in, copy the `accessToken`, click **Authorize** and paste it; every call then sends the token. `GET /{code}` is hidden from Swagger, because a browser `fetch` follows the `302` to the external site and fails CORS. In Swagger, use `GET /api/preview/{code}` to see where a link points without counting a click. To follow a link for real, open the short URL in a browser tab or use `curl -i`.

### Authentication

Accounts use email + password and stateless **JWT** access tokens (RS256, valid for 1 hour). Send the token on every call except the public ones:

```
Authorization: Bearer <accessToken>
```

| Public (no token) | Needs a token |
|---|---|
| `POST /api/auth/register`, `POST /api/auth/login` | `POST /api/shorten` |
| `GET /{code}` (the redirect itself) | `GET /api/urls`, `DELETE /api/urls/{code}` |
| `GET /api/preview/{code}` | `GET /api/analytics/{code}`, `GET /api/auth/me` |

Links belong to the user who created them. Listing, analytics and deleting only ever see **your own** links; someone else's link answers `404`, as if it didn't exist.

**Sign up** (`POST /api/auth/register`, returns `201`) or **log in** (`POST /api/auth/login`):

```bash
curl -X POST http://localhost:8081/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com","password":"correct-horse-battery"}'
```

```json
{
  "accessToken": "eyJhbGciOiJSUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "userId": 1,
  "email": "alice@example.com"
}
```

Passwords must be 8–72 characters and are stored only as BCrypt hashes. Emails are case-insensitive. A wrong password and an unknown email both return the same `401 Invalid email or password`. An email that's already registered returns `409`.

The examples below assume the token is in a shell variable:

```bash
TOKEN=$(curl -s -X POST http://localhost:8081/api/auth/login -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com","password":"correct-horse-battery"}' | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
```

### Shorten a URL

`POST /api/shorten`

Each URL gets one short link per user. If you shorten a URL you shortened before, you get your existing short link back; another user shortening the same URL gets their own link and their own click count. URLs are normalized first, so `https://github.com`, `https://github.com/` and `HTTPS://GitHub.com:443/` all count as the same link. Paths and query strings are kept as-is. Only `http://` and `https://` URLs are accepted; anything else returns `400`.

```bash
curl -X POST http://localhost:8081/api/shorten \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://www.google.com/search?q=spring+boot+kafka+redis"}'
```

Response:

```json
{
  "shortCode": "tJvSh8k",
  "shortUrl": "http://localhost:8081/tJvSh8k",
  "originalUrl": "https://www.google.com/search?q=spring+boot+kafka+redis"
}
```

> **Windows (Command Prompt):** use `^` for line breaks and escape the quotes: `-d "{\"url\":\"https://example.com\"}"`

### Follow a short link

`GET /{code}` is public and returns `302 Found` with a `Location` header pointing at the original URL.

```bash
curl -i http://localhost:8081/tJvSh8k
```

### Get click analytics

`GET /api/analytics/{code}` (your own links only)

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/analytics/tJvSh8k
```

Response:

```json
{
  "shortCode": "tJvSh8k",
  "originalUrl": "https://www.google.com/search?q=spring+boot+kafka+redis",
  "totalClicks": 3,
  "createdAt": "2026-10-01T06:58:23.36994"
}
```

### List my short links

`GET /api/urls?page=0&size=20` returns your links, newest first, with click counts. `size` is capped at 100.

```bash
curl -H "Authorization: Bearer $TOKEN" "http://localhost:8081/api/urls?page=0&size=2"
```

```json
{
  "items": [
    {
      "shortCode": "tJvSh8k",
      "shortUrl": "http://localhost:8081/tJvSh8k",
      "originalUrl": "https://www.wikipedia.org",
      "totalClicks": 2,
      "createdAt": "2026-11-27T07:06:46.359157"
    }
  ],
  "page": 0,
  "size": 2,
  "totalElements": 11,
  "totalPages": 6
}
```

### Delete a short link

`DELETE /api/urls/{code}` returns `204`. The short URL stops working immediately, its click count is removed, and the code is never handed out again.

```bash
curl -X DELETE -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/urls/tJvSh8k
```

### Errors

Every error uses the same shape. An unknown short code (or someone else's) returns `404`:

```json
{
  "status": 404,
  "message": "Short URL not found: abc",
  "timestamp": "2026-10-01T07:05:12.123Z"
}
```

A missing, invalid or expired token returns `401` with a message saying which.

---

## Try the full pipeline

```bash
# 1. Sign up and keep the token
TOKEN=$(curl -s -X POST http://localhost:8081/api/auth/register -H "Content-Type: application/json" \
  -d '{"email":"me@example.com","password":"my-password-123"}' | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')

# 2. Shorten a URL (note the shortCode in the response, e.g. tJvSh8k)
curl -X POST http://localhost:8081/api/shorten -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"url":"https://www.google.com"}'

# 3. Click the short link a few times (public, no token)
curl -i http://localhost:8081/tJvSh8k
curl -i http://localhost:8081/tJvSh8k
curl -i http://localhost:8081/tJvSh8k

# 4. Check the click count
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/analytics/tJvSh8k      # totalClicks: 3
```

To watch the cache and the event pipeline directly:

```bash
# See the cached URL and the click counter inside Redis
docker exec -it my-redis redis-cli
GET url:tJvSh8k
GET clicks:tJvSh8k

# Follow the app logs to see the Kafka consumer processing each click
docker-compose logs -f app
```

---

## Project structure

```
url-shortener/
├── Dockerfile
├── docker-compose.yml
├── pom.xml
├── src/test/                # unit and web-layer tests (see "Running tests")
└── src/main/
    ├── java/com/subhranil/url_shortner/
    │   ├── auth/         Accounts: AuthController, AuthService, TokenService, AppUser (self-contained module)
    │   ├── security/     SecurityConfig, JwtConfig (RS256 keys), CurrentUser
    │   ├── controller/   UrlController
    │   ├── service/      UrlService, ClickEventProducer, ClickEventConsumer, KafkaCircuitBreaker, ...
    │   ├── repository/   UrlMappingRepository
    │   ├── model/        UrlMapping, ClickEvent
    │   ├── dto/          ShortenRequest, ShortenResponse, AnalyticsResponse, UrlSummary, PagedResponse
    │   ├── config/       OpenApiConfig, KafkaListenerStarter
    │   └── exception/    GlobalExceptionHandler, ErrorResponse, ResourceNotFoundException
    └── resources/
        ├── application.yml          # local profile (services on localhost)
        ├── application-docker.yml   # used inside Docker Compose
        └── db/migration/            # Flyway SQL migrations (V1__create_url_mapping.sql, ...)
```

---

## Roadmap

- [ ] Link expiry (TTL on the Redis cache and an `expiresAt` field in PostgreSQL)
- [ ] Custom short codes
- [ ] Rate limiting per client with Redis
- [x] Stricter URL validation (http/https only)
- [x] Resilient click publishing, so a Kafka outage never affects redirects
- [x] Unit and web-layer tests
- [ ] Integration tests with Testcontainers (real PostgreSQL, Redis and Kafka)
- [x] Versioned database migrations (Flyway)
- [x] Swagger / OpenAPI documentation
- [x] User accounts, so each user lists only their own links
- [ ] Password reset and email verification
- [ ] Refresh tokens (access tokens currently expire after 1 hour)
- [ ] Persist Redis click counters across restarts

---

## Author

**Subhranil Ghosh**, Java Backend Developer
[LinkedIn](https://www.linkedin.com/in/subhranilghosh) · [Portfolio](https://subhranilghosh1997.github.io/portfolio/)
