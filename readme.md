# URL Shortener

A URL shortener REST API with click analytics, built with **Java 21**, **Spring Boot**, **PostgreSQL**, **Redis** and **Apache Kafka**, and fully containerized with **Docker Compose**.

Redirects are served from a Redis cache. Click tracking runs asynchronously through Kafka, so the redirect never waits on analytics processing.

---

## Tech stack

| Layer | Technology | Why it's here |
|---|---|---|
| API | Java 21, Spring Boot | REST endpoints, business logic |
| Database | PostgreSQL 16 | Permanent source of truth for URL mappings |
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

1. **Shorten:** the URL is saved to PostgreSQL. The generated row ID is Base62-encoded into the short code (for example ID `125` becomes `21`), and the mapping is written to Redis.
2. **Redirect:** the app looks in Redis first (cache-aside). On a miss it reads PostgreSQL and repopulates the cache. It then publishes a click event to Kafka and returns an HTTP `302`.
3. **Count clicks:** a Kafka consumer reads each event and runs an atomic `INCR` on a Redis counter for that short code.
4. **Analytics:** the analytics endpoint reads the counter from Redis.

### Design decisions

- **Cache-aside with Redis:** the redirect is the hottest path. Serving it from memory keeps PostgreSQL out of the common case.
- **Kafka for analytics:** counting a click is a side effect, not part of the redirect. Publishing an event lets the two run independently.
- **Redis `INCR` for counters:** the increment is atomic, so concurrent clicks can't corrupt the count.
- **Base62 from the database ID:** short codes are unique by construction, with no collision checks.

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

### Stop it

```bash
docker-compose down        # stop containers, keep PostgreSQL data
docker-compose down -v     # stop containers and delete the PostgreSQL volume
```

---

## API

Base URL: `http://localhost:8081`

### Shorten a URL

`POST /api/shorten`

```bash
curl -X POST http://localhost:8081/api/shorten \
  -H "Content-Type: application/json" \
  -d '{"url":"https://www.google.com/search?q=spring+boot+kafka+redis"}'
```

Response:

```json
{
  "shortCode": "1",
  "shortUrl": "http://localhost:8081/1",
  "originalUrl": "https://www.google.com/search?q=spring+boot+kafka+redis"
}
```

> **Windows (Command Prompt):** use `^` for line breaks and escape the quotes: `-d "{\"url\":\"https://example.com\"}"`

### Follow a short link

`GET /{code}` returns `302 Found` with a `Location` header pointing at the original URL.

```bash
curl -i http://localhost:8081/1
```

### Get click analytics

`GET /api/analytics/{code}`

```bash
curl http://localhost:8081/api/analytics/1
```

Response:

```json
{
  "shortCode": "1",
  "originalUrl": "https://www.google.com/search?q=spring+boot+kafka+redis",
  "totalClicks": 3,
  "createdAt": "2026-10-01T06:58:23.36994"
}
```

### Errors

An unknown short code returns `404`:

```json
{
  "status": 404,
  "message": "Short URL not found: abc",
  "timestamp": "2026-10-01T07:05:12.123Z"
}
```

---

## Try the full pipeline

```bash
# 1. Shorten a URL
curl -X POST http://localhost:8081/api/shorten -H "Content-Type: application/json" \
  -d '{"url":"https://www.google.com"}'

# 2. Click the short link a few times
curl -i http://localhost:8081/1
curl -i http://localhost:8081/1
curl -i http://localhost:8081/1

# 3. Check the click count
curl http://localhost:8081/api/analytics/1      # totalClicks: 3
```

To watch the cache and the event pipeline directly:

```bash
# See the cached URL and the click counter inside Redis
docker exec -it my-redis redis-cli
GET url:1
GET clicks:1

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
    │   ├── controller/   UrlController
    │   ├── service/      UrlService, ClickEventProducer, ClickEventConsumer
    │   ├── repository/   UrlMappingRepository
    │   ├── model/        UrlMapping, ClickEvent
    │   ├── dto/          ShortenRequest, ShortenResponse, AnalyticsResponse
    │   └── exception/    GlobalExceptionHandler, ErrorResponse, ResourceNotFoundException
    └── resources/
        ├── application.yml          # local profile (services on localhost)
        └── application-docker.yml   # used inside Docker Compose
```

---

## Roadmap

- [ ] Link expiry (TTL on the Redis cache and an `expiresAt` field in PostgreSQL)
- [ ] Custom short codes
- [ ] Rate limiting per client with Redis
- [ ] Stricter URL validation (http/https only)
- [ ] Resilient click publishing, so a Kafka outage never affects redirects
- [ ] Unit and integration tests
- [ ] Swagger / OpenAPI documentation
- [ ] Persist Redis click counters across restarts

---

## Author

**Subhranil Ghosh**, Java Backend Developer
[LinkedIn](https://www.linkedin.com/in/subhranilghosh) · [Portfolio](https://subhranilghosh1997.github.io/portfolio/)
