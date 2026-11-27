# URL Shortener

A URL shortener REST API with click analytics, built with **Java 21**, **Spring Boot**, **PostgreSQL**, **Redis** and **Apache Kafka**, and fully containerized with **Docker Compose**.

Redirects are served from a Redis cache. Click tracking runs asynchronously through Kafka, so the redirect never waits on analytics processing.

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
                     POST /api/shorten
   Client ─────────────────────────────────▶ ┌───────────────────┐
                                             │                   │──save──▶ PostgreSQL
                                             │   Spring Boot     │
                                             │   application     │──cache─▶ Redis  (url:{code})
                                             │                   │
   Client ──── GET /{code} ────────────────▶ │ 1. Check Redis    │
          ◀─── 302 redirect ──────────────── │ 2. Fallback to DB │
                                             │ 3. Publish event  │──event─▶ Kafka topic
                                             └───────────────────┘          "url-click-events"
                                                                                    │
                                             ┌───────────────────┐                  │
                                             │  Kafka consumer   │◀─────────────────┘
                                             │  (analytics-group)│
                                             │  INCR clicks:{code}──────▶ Redis
                                             └───────────────────┘

   Client ──── GET /api/analytics/{code} ──▶ reads clicks:{code} from Redis
```

### How it works

1. **Shorten:** the URL is validated and normalized. If it was shortened before, the existing code is returned. Otherwise it is saved to PostgreSQL. The generated row ID is scrambled into a random-looking 7-character Base62 short code (for example `tJvSh8k`), and the mapping is written to Redis.
2. **Redirect:** the app looks in Redis first (cache-aside). On a miss it reads PostgreSQL and repopulates the cache. It then publishes a click event to Kafka and returns an HTTP `302`.
3. **Count clicks:** a Kafka consumer reads each event and runs an atomic `INCR` on a Redis counter for that short code.
4. **Analytics:** the analytics endpoint reads the counter from Redis.

### Design decisions

- **Cache-aside with Redis:** the redirect is the hottest path. Serving it from memory keeps PostgreSQL out of the common case.
- **Kafka for analytics:** counting a click is a side effect, not part of the redirect. Publishing an event lets the two run independently. If Kafka is unavailable, publishing gives up after 500 ms and the click is counted directly in Redis instead. A circuit breaker then skips Kafka for 30 seconds at a time, so during an outage redirects stay as fast as normal. The app also starts and runs without Kafka, and reconnects by itself when Kafka is back.
- **Redis `INCR` for counters:** the increment is atomic, so concurrent clicks can't corrupt the count.
- **Scrambled IDs as short codes:** the database ID goes through a one-to-one scramble (a Feistel permutation) and is Base62-encoded to 7 characters. Codes look random and don't reveal how many links exist, but they are still unique by construction, so there are no collision checks.

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

Wait until the logs show the app has started and the Kafka consumer has joined the `analytics-group`. The first start takes a minute or two because images are downloaded.

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
- [ ] Unit and integration tests
- [x] Swagger / OpenAPI documentation
- [x] User accounts, so each user lists only their own links
- [ ] Password reset and email verification
- [ ] Refresh tokens (access tokens currently expire after 1 hour)
- [ ] Persist Redis click counters across restarts

---

## Author

**Subhranil Ghosh**, Java Backend Developer
[LinkedIn](https://www.linkedin.com/in/subhranilghosh) · [Portfolio](https://subhranilghosh1997.github.io/portfolio/)
