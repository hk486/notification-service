# Notification Service — Complete Project Guide

> A production-grade multi-channel notification system built with **Spring Boot 3**, **Apache Kafka**, **MySQL**, **Redis**, **AWS SES**, **Twilio**, and **Firebase FCM**.
> Built incrementally over 7 days. This document explains everything — what each piece is, why it exists, and how it fits together.

---

## Table of Contents

1. [What This Project Does](#1-what-this-project-does)
2. [Architecture Overview](#2-architecture-overview)
3. [Tech Stack](#3-tech-stack)
4. [Project Structure](#4-project-structure)
5. [Day-by-Day Implementation](#5-day-by-day-implementation)
   - [Day 1 — Project Setup & Docker](#day-1--project-setup--docker)
   - [Day 2 — Database Models & Flyway Migrations](#day-2--database-models--flyway-migrations)
   - [Day 3 — Kafka Setup & Message Producer](#day-3--kafka-setup--message-producer)
   - [Day 4 — REST API & Email Provider](#day-4--rest-api--email-provider)
   - [Day 5 — SMS & Push Notification Providers](#day-5--sms--push-notification-providers)
   - [Day 6 — Redis Idempotency & Rate Limiting](#day-6--redis-idempotency--rate-limiting)
   - [Day 7 — Resilience4j Circuit Breaker & Retry](#day-7--resilience4j-circuit-breaker--retry)
6. [How a Notification Flows End-to-End](#6-how-a-notification-flows-end-to-end)
7. [Database Design](#7-database-design)
8. [Kafka Topics](#8-kafka-topics)
9. [Redis Keys](#9-redis-keys)
10. [API Reference](#10-api-reference)
11. [Configuration Reference](#11-configuration-reference)
12. [How to Run Locally](#12-how-to-run-locally)
13. [Key Design Decisions Explained](#13-key-design-decisions-explained)
14. [Switching from Mock to Real Providers](#14-switching-from-mock-to-real-providers)

---

## 1. What This Project Does

This service receives a notification request (e.g. "send an OTP to this user via EMAIL") over a REST API, queues it into Apache Kafka, and then asynchronously delivers it via the appropriate channel (Email / SMS / Push).

**Key capabilities:**
- Send notifications via **3 channels**: EMAIL, SMS, PUSH
- Route by **3 priority levels**: HIGH (OTP/Alerts), MEDIUM (Order updates), LOW (Promotions)
- **Prevent duplicates** — same request sent twice is rejected (idempotency)
- **Prevent spam** — max 5 notifications per user per channel per minute (rate limiting)
- **Handle provider failures gracefully** — retry 3 times, then open circuit breaker (resilience)
- **Log every notification** in MySQL with full audit trail (PENDING → SENT / FAILED)
- **Swagger UI** at `http://localhost:8080/swagger-ui.html` for manual testing

---

## 2. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        REST API Layer                            │
│  POST /api/v1/notifications                                      │
│       │                                                          │
│  1. Validate request body (@Valid)                               │
│  2. Check Redis idempotency key → reject if duplicate (409)     │
│  3. Check Redis rate limit → reject if too many requests (429)  │
│  4. Save NotificationLog to MySQL (status = PENDING)            │
│  5. Publish JSON to Kafka topic (high/medium/low)               │
└────────────────────────┬────────────────────────────────────────┘
                         │ Kafka
         ┌───────────────┼───────────────┐
         ▼               ▼               ▼
  EmailConsumer    SmsConsumer    PushConsumer
  (group: email)   (group: sms)   (group: push)
         │               │               │
         └───────────────┼───────────────┘
                         │ all delegate to
                         ▼
            NotificationDispatchService
               @CircuitBreaker + @Retry
         ┌──────────┬────────────┐
         ▼          ▼            ▼
   EmailProvider  SmsProvider  PushProvider
  (AWS SES or    (Twilio or   (Firebase FCM
   LogEmail)      LogSms)      or LogPush)
         │
         ▼
  MySQL: NotificationLog status → SENT or FAILED
```

**The flow in one sentence:**  
`HTTP request → Redis guardrails → MySQL log → Kafka queue → Consumer → Dispatch service (with circuit breaker + retry) → Provider → DB updated`

---

## 3. Tech Stack

### Core Framework

| Tool | Version | What it does |
|------|---------|-------------|
| **Spring Boot** | 3.5.14 | Main application framework — wires everything together automatically |
| **Java** | 17 (runs on 21) | Programming language |
| **Maven** | (via `mvnw`) | Build tool — downloads dependencies, compiles, packages |
| **Lombok** | (via Spring Boot) | Removes boilerplate Java code (`@Data`, `@Builder`, `@Slf4j`, etc.) |

### Databases & Messaging

| Tool | Version | What it does |
|------|---------|-------------|
| **MySQL** | 8.0 | Stores every notification — its status, channel, provider, timestamps |
| **Redis** | 7.0 | Two jobs: (1) stores idempotency keys, (2) counts requests for rate limiting |
| **Apache Kafka** | 7.4.0 (Confluent) | Message queue — decouples API from delivery so slow providers don't block HTTP responses |
| **Zookeeper** | 7.4.0 (Confluent) | Kafka's internal coordinator (tells brokers who is leader etc.) |

### Spring Starter Libraries (auto-configured)

| Starter | What it gives you |
|---------|------------------|
| `spring-boot-starter-web` | REST controllers, Jackson JSON, embedded Tomcat |
| `spring-boot-starter-data-jpa` | Hibernate ORM — Java objects ↔ MySQL rows |
| `spring-boot-starter-data-redis` | `RedisTemplate` — talk to Redis |
| `spring-kafka` | `KafkaTemplate` (producer) + `@KafkaListener` (consumer) |
| `spring-boot-starter-validation` | `@Valid`, `@NotBlank`, `@NotNull` on request DTOs |
| `spring-boot-starter-actuator` | Health, metrics, circuit-breaker state endpoints |
| `spring-boot-starter-aop` | Proxy magic — makes `@CircuitBreaker`, `@Retry` annotations work |

### External Provider SDKs

| SDK | What it does |
|-----|-------------|
| **AWS SDK v2 (sesv2)** | Sends real emails via Amazon Simple Email Service |
| **Twilio SDK 10.1.3** | Sends real SMS messages via Twilio's REST API |
| **Firebase Admin SDK 9.3.0** | Sends real push notifications via Firebase Cloud Messaging (FCM) |

### Resilience

| Library | What it does |
|---------|-------------|
| `spring-cloud-starter-circuitbreaker-resilience4j` | Spring Cloud abstraction over Resilience4j |
| `resilience4j-spring-boot3` | Enables `@CircuitBreaker`, `@Retry` annotations with Spring Boot 3 autoconfiguration |

### API Documentation

| Library | What it does |
|---------|-------------|
| `springdoc-openapi-starter-webmvc-ui 2.8.16` | Auto-generates Swagger UI at `/swagger-ui.html` from your `@RestController` annotations |

### Database Migrations

| Library | What it does |
|---------|-------------|
| `flyway-mysql` | Tracks and runs SQL scripts in order — so the schema is always at the right version |

### Observability

| Library | What it does |
|---------|-------------|
| `micrometer-registry-prometheus` | Exposes metrics at `/actuator/prometheus` for Prometheus/Grafana scraping |

---

## 4. Project Structure

```
notification-service/
├── docker-compose.yml                  ← all 4 Docker containers
├── pom.xml                             ← dependencies
└── src/main/
    ├── resources/
    │   ├── application.yaml            ← all configuration
    │   └── db/migration/               ← Flyway SQL scripts
    │       ├── V1__create_notification_logs.sql
    │       ├── V2__create_notification_templates.sql
    │       └── V3__create_user_preferences.sql
    └── java/notification_service/
        ├── NotificationServiceApplication.java   ← main entry point
        │
        ├── api/                        ← REST layer
        │   ├── NotificationController.java
        │   ├── GlobalExceptionHandler.java
        │   └── dto/
        │       ├── NotificationRequest.java
        │       └── NotificationResponse.java
        │
        ├── config/                     ← Spring configuration classes
        │   ├── KafkaConfig.java        ← creates 3 Kafka topics
        │   ├── RedisConfig.java        ← configures RedisTemplate
        │   ├── AwsConfig.java          ← creates SesV2Client (conditional)
        │   ├── TwilioConfig.java       ← initialises Twilio SDK (conditional)
        │   └── FirebaseConfig.java     ← initialises Firebase SDK (conditional)
        │
        ├── model/                      ← JPA entities (DB tables)
        │   ├── NotificationLog.java
        │   ├── NotificationTemplate.java
        │   └── UserPreference.java
        │
        ├── repository/                 ← Spring Data JPA repositories (DB queries)
        │   ├── NotificationLogRepository.java
        │   ├── NotificationTemplateRepository.java
        │   └── UserPreferenceRepository.java
        │
        ├── producer/
        │   └── NotificationProducer.java   ← publishes to Kafka
        │
        ├── consumer/                   ← Kafka listeners
        │   ├── EmailConsumer.java
        │   ├── SmsConsumer.java
        │   └── PushConsumer.java
        │
        ├── service/                    ← Business logic
        │   ├── NotificationDispatchService.java  ← circuit breaker + retry
        │   ├── RedisIdempotencyService.java
        │   └── RedisRateLimiterService.java
        │
        ├── provider/                   ← External provider wrappers
        │   ├── email/
        │   │   ├── EmailProvider.java          ← interface
        │   │   ├── AwsSesProvider.java         ← real (conditional)
        │   │   └── LogEmailProvider.java       ← mock (default)
        │   ├── sms/
        │   │   ├── SmsProvider.java            ← interface
        │   │   ├── TwilioSmsProvider.java      ← real (conditional)
        │   │   └── LogSmsProvider.java         ← mock (default)
        │   └── push/
        │       ├── PushProvider.java           ← interface
        │       ├── FirebasePushProvider.java   ← real (conditional)
        │       └── LogPushProvider.java        ← mock (default)
        │
        └── exception/
            ├── DuplicateRequestException.java   ← triggers 409 Conflict
            └── RateLimitExceededException.java  ← triggers 429 Too Many Requests
```

---

## 5. Day-by-Day Implementation

---

### Day 1 — Project Setup & Docker

**Goal:** Get MySQL, Kafka, Redis, and the Spring Boot app all talking to each other.

**What was created:**
- `docker-compose.yml` — defines 4 containers: `zookeeper`, `kafka`, `mysql`, `redis`
- `application.yaml` — central config wiring the app to all services

**Why Docker Compose?**  
Instead of installing MySQL/Kafka/Redis on your laptop, Docker runs them in isolated containers. One command (`docker compose up -d`) starts everything.

**Containers explained:**

| Container | Image | Port | Purpose |
|-----------|-------|------|---------|
| `zookeeper` | confluentinc/cp-zookeeper:7.4.0 | 2181 | Kafka's internal leader election coordinator |
| `kafka` | confluentinc/cp-kafka:7.4.0 | 9092 | Message broker — holds queues (topics) |
| `mysql` | mysql:8.0 | 3306 | Relational DB — stores notification logs |
| `redis` | redis:7.0 | 6379 | In-memory key-value store — idempotency + rate limiting |

**Common issue:** If MySQL on port 3306 is already running on your machine, stop the system service first:
```bash
sudo systemctl stop mysql
docker compose up -d
```

---

### Day 2 — Database Models & Flyway Migrations

**Goal:** Create the 3 database tables with proper structure, managed by Flyway.

**What is Flyway?**  
Flyway is a database version control tool. Instead of manually running SQL on the DB, you write numbered SQL files (like `V1__...`, `V2__...`). Flyway runs them in order and tracks which ones have already been applied. If you add a new `V4__...` file next week, Flyway only runs that new one.

**The 3 tables:**

#### `notification_logs` (V1)
The audit trail — every notification attempt is recorded here.

| Column | Type | Purpose |
|--------|------|---------|
| `id` | BIGINT AUTO_INCREMENT | Primary key |
| `user_id` | VARCHAR(255) | Who to send to |
| `channel` | ENUM(EMAIL,SMS,PUSH) | How to send |
| `status` | ENUM(PENDING,SENT,FAILED,DUPLICATE) | Current state |
| `provider` | VARCHAR(100) | Which provider was used (AWS_SES, TWILIO, etc.) |
| `message` | TEXT | The notification content |
| `idempotency_key` | VARCHAR(255) UNIQUE | Prevents duplicates |
| `failure_reason` | TEXT | Why it failed (if applicable) |
| `created_at` | DATETIME | When the request came in |
| `updated_at` | DATETIME | Last status change |

#### `notification_templates` (V2)
Pre-built message templates so callers don't need to write message text every time.

| Column | Type | Purpose |
|--------|------|---------|
| `id` | BIGINT | Primary key |
| `name` | VARCHAR(100) UNIQUE | Template identifier (e.g. "OTP", "ORDER") |
| `body` | TEXT | Message body with `{placeholder}` variables |
| `channel` | ENUM | Which channel this template is for |

Pre-seeded templates: `OTP_EMAIL`, `ORDER_CONFIRMATION_EMAIL`, `WELCOME_EMAIL`, `PROMO_EMAIL`

#### `user_preferences` (V3)
Per-user settings — whether they want notifications, and "Do Not Disturb" hours.

| Column | Type | Purpose |
|--------|------|---------|
| `user_id` + `channel` | UNIQUE pair | One preference row per user per channel |
| `enabled` | BOOLEAN (default true) | Can this user receive notifications on this channel? |
| `dnd_start` | TIME | Do Not Disturb start time (e.g. 22:00) |
| `dnd_end` | TIME | Do Not Disturb end time (e.g. 08:00) |

**The 3 JPA Repositories** (Spring Data JPA auto-generates the SQL):
- `NotificationLogRepository` — `findByUserId()`, `findByStatus()`, `findByIdempotencyKey()`
- `NotificationTemplateRepository` — `findByName()`, `findByNameAndChannel()`
- `UserPreferenceRepository` — `findByUserId()`, `findByUserIdAndChannel()`

---

### Day 3 — Kafka Setup & Message Producer

**Goal:** Create Kafka topics and a producer that routes messages to the right topic based on priority.

**What is Kafka?**  
Kafka is a distributed message queue. Think of it as a post office with 3 mailboxes (topics). The REST API drops a letter (message) into a mailbox. The consumers (email worker, SMS worker, push worker) pick up letters from the mailboxes at their own pace.

**Why use Kafka instead of calling providers directly from the API?**
- The HTTP response is instant (202 Accepted) — the user doesn't wait for AWS SES to respond
- If 10,000 OTPs arrive at once, Kafka queues them; providers process at their own speed
- If a provider goes down, messages wait in Kafka and are delivered when it recovers

**The 3 topics:**

| Topic | Priority | Use cases |
|-------|----------|-----------|
| `notification.high` | HIGH | OTPs, fraud alerts, password resets |
| `notification.medium` | MEDIUM | Order confirmation, shipping updates |
| `notification.low` | LOW | Promotions, newsletters |

**`KafkaConfig`** — creates the topics at startup with `NewTopic` beans (3 partitions each).

**`NotificationProducer`**:
- Converts `NotificationRequest` to JSON string
- Uses `userId` as the **Kafka partition key** — this means all messages for the same user go to the same partition, preserving order
- Uses `CompletableFuture` for async success/failure logging

---

### Day 4 — REST API & Email Provider

**Goal:** Build the HTTP endpoint, validate requests, and implement email delivery.

**`NotificationController`** — `POST /api/v1/notifications`

Full request flow:
1. `@Valid` validates the request body (userId not blank, channel not null, etc.)
2. Auto-generate `idempotencyKey` (UUID) if caller didn't provide one
3. **Redis idempotency check** — reject if key seen before (added Day 6)
4. **Redis rate limit check** — reject if user exceeded limit (added Day 6)
5. Save `NotificationLog` to MySQL with `status = PENDING`
6. Publish to correct Kafka topic
7. Return `202 ACCEPTED` immediately

**Provider Pattern** — Same concept for all 3 channels:

```
EmailProvider (interface)
    ├── AwsSesProvider   ← active when aws.ses.enabled=true
    └── LogEmailProvider ← active when aws.ses.enabled=false (DEFAULT)
```

`LogEmailProvider` just logs the email to the console — perfect for local development without real AWS credentials.

**`GlobalExceptionHandler`** (`@RestControllerAdvice`):
- `MethodArgumentNotValidException` → `400 Bad Request` with field→message map
- `DuplicateRequestException` → `409 Conflict`
- `RateLimitExceededException` → `429 Too Many Requests`
- `Exception` (catch-all) → `500 Internal Server Error`

**`AwsConfig`** — creates `SesV2Client` bean using `@ConditionalOnProperty` so it only loads when `aws.ses.enabled=true`. Spring won't try to connect to AWS during local dev.

**`EmailConsumer`** — `@KafkaListener` on all 3 topics with `groupId = email-consumer-group`. Filters messages where `channel == EMAIL`, ignores the rest.

---

### Day 5 — SMS & Push Notification Providers

**Goal:** Add SMS (Twilio) and Push (Firebase FCM) channels, following the exact same provider pattern as email.

**Twilio (SMS):**

```
SmsProvider (interface)
    ├── TwilioSmsProvider   ← active when twilio.enabled=true
    └── LogSmsProvider      ← active by default (logs to console)
```

`TwilioConfig` calls `Twilio.init(accountSid, authToken)` via `@PostConstruct`. Twilio SDK is static — it stores credentials globally, not as a bean. Once initialised, `TwilioSmsProvider` just calls `Message.creator(...).create()`.

**Firebase (Push):**

```
PushProvider (interface)
    ├── FirebasePushProvider  ← active when firebase.enabled=true
    └── LogPushProvider       ← active by default (logs to console)
```

`FirebaseConfig` calls `FirebaseApp.initializeApp()` with the service-account JSON file. After that, `FirebasePushProvider` uses `FirebaseMessaging.getInstance().send(message)`.

**Setup for real Firebase:**
1. Firebase Console → Project Settings → Service Accounts → Generate New Private Key
2. Save the downloaded `.json` as `src/main/resources/firebase-service-account.json`
3. Set `firebase.enabled: true` in `application.yaml`

**`SmsConsumer`** and **`PushConsumer`** — same structure as `EmailConsumer`. Each listens on all 3 topics, filters by their own channel, ignores the rest.

---

### Day 6 — Redis Idempotency & Rate Limiting

**Goal:** Add two invisible safety guards that protect the system from abuse and mistakes.

#### Guard 1: Idempotency (prevents duplicates)

**Problem:** A mobile app might retry a failed network request and accidentally send the same OTP twice.

**Solution:** Every request has an `idempotencyKey`. When a request comes in:
1. Redis: `SET idempotency:<key> "1" NX EX 86400` (NX = only set if NOT exists, EX = expire after 24h)
2. If Redis returns `true` → key was NEW → proceed normally
3. If Redis returns `false` → key ALREADY EXISTS → return `409 Conflict`

```
First request with key "abc-123"  → Redis: sets key → proceeds → 202 ACCEPTED
Second request with key "abc-123" → Redis: key exists → 409 CONFLICT ← duplicate rejected!
```

Redis key format: `idempotency:<idempotencyKey>`  
TTL: 24 hours (configurable via `notification.idempotency.ttl-hours`)

#### Guard 2: Rate Limiting (prevents spam)

**Problem:** A bug in a calling service might send 1000 emails in a second for the same user.

**Solution:** Count requests per user per channel in a 60-second window:
1. Redis: `INCR rate:<userId>:<CHANNEL>` — atomically increment counter
2. If counter == 1 (first request this window) → also `EXPIRE 60` to reset after 60 seconds
3. If counter > limit → return `429 Too Many Requests`

```
Request 1→5: counter goes 1,2,3,4,5 → all ACCEPTED
Request 6:  counter is 6, limit is 5 → 429 TOO MANY REQUESTS
After 60 seconds: counter key expires → window resets → user can send again
```

Redis key format: `rate:<userId>:<CHANNEL>`  
TTL: 60 seconds (configurable via `notification.rate-limit.window-seconds`)

**`RedisConfig`** — configures `RedisTemplate<String, String>` with `StringRedisSerializer` so all Redis keys/values are human-readable plain text (instead of binary blobs).

---

### Day 7 — Resilience4j Circuit Breaker & Retry

**Goal:** Make the notification delivery fault-tolerant — automatically retry on failure, and stop hammering a broken provider.

#### What is a Circuit Breaker?

Think of a trip switch (circuit breaker) in your house's electrical panel:
- **CLOSED** — electricity flows normally. Requests go to the provider.
- **OPEN** — switch tripped. No electricity. Requests are blocked, fallback runs instantly.
- **HALF-OPEN** — try 2 test requests. If they pass, close the circuit again.

The circuit trips when ≥50% of the last 5 calls fail.

#### What is Retry?

Before counting a call as "failed", try it again:
- Attempt 1 fails → wait 500ms → Attempt 2 fails → wait 1s → Attempt 3 fails → give up

Delays use **exponential backoff** (each wait is 2× the previous) — this avoids flooding a struggling service.

#### How they work together

```
dispatchService.sendEmail(request)
    │
    ▼ CircuitBreaker checks: is circuit CLOSED?
    │   YES → proceed     NO (OPEN) → skip to emailFallback()
    │
    ▼ Retry wrapper
    │
    ▼ emailProvider.send()
    │   SUCCESS → update DB to SENT
    │   FAILURE → retry (up to 3 times with exponential backoff)
    │       All retries exhausted → emailFallback()
    │
    ▼ emailFallback()
        → log error
        → update DB to FAILED
```

#### `NotificationDispatchService`

This is the central dispatch layer (created Day 7). All 3 consumers now delegate to this service instead of calling providers directly. Benefits:
- Single place for all resilience logic
- Consumers stay thin (just filter by channel)
- Status updates (SENT/FAILED) happen in one place

#### Circuit Breaker Settings (per channel)

| Setting | Value | Meaning |
|---------|-------|---------|
| `sliding-window-size` | 5 | Look at the last 5 calls |
| `minimum-number-of-calls` | 3 | Need at least 3 calls before evaluating |
| `failure-rate-threshold` | 50% | Trip if ≥50% failed |
| `wait-duration-in-open-state` | 10s | Stay OPEN for 10 seconds, then try HALF-OPEN |
| `permitted-number-of-calls-in-half-open-state` | 2 | Try 2 test calls in HALF-OPEN |

#### Retry Settings (per channel)

| Setting | Value | Meaning |
|---------|-------|---------|
| `max-attempts` | 3 | Try up to 3 times |
| `wait-duration` | 500ms | First wait is 500ms |
| `exponential-backoff-multiplier` | 2 | 500ms → 1000ms → 2000ms |

#### Live Metrics

Check circuit breaker state: `GET /actuator/metrics/resilience4j.circuitbreaker.state?tag=name:email-cb`
- `1.0` = CLOSED (healthy)
- `0.0` = OPEN (tripped)
- `0.5` = HALF-OPEN (recovering)

Check retry call counts: `GET /actuator/metrics/resilience4j.retry.calls?tag=name:email-retry`

---

## 6. How a Notification Flows End-to-End

Example: Send OTP via EMAIL

```
Step 1: Client sends HTTP POST
  POST /api/v1/notifications
  {
    "userId": "harmeet@example.com",
    "channel": "EMAIL",
    "priority": "HIGH",
    "message": "Your OTP is 123456",
    "idempotencyKey": "req-abc-001"   ← optional, auto-generated if missing
  }

Step 2: NotificationController validates request
  → @Valid checks: userId not blank ✓, channel not null ✓, priority not null ✓

Step 3: Redis idempotency check
  → Redis: SET idempotency:req-abc-001 "1" NX EX 86400
  → Returns true (new key) → proceed
  → If second call with same key → returns false → throw DuplicateRequestException → 409

Step 4: Redis rate limit check
  → Redis: INCR rate:harmeet@example.com:EMAIL  → returns 1
  → Redis: EXPIRE rate:harmeet@example.com:EMAIL 60
  → 1 ≤ 5 → proceed
  → If 6th request in 60s → returns 6 → throw RateLimitExceededException → 429

Step 5: Save to MySQL
  → INSERT INTO notification_logs (user_id, channel, status, ...)
    VALUES ('harmeet@example.com', 'EMAIL', 'PENDING', ...)

Step 6: Publish to Kafka
  → KafkaTemplate.send("notification.high", "harmeet@example.com", <JSON payload>)
  → Return 202 ACCEPTED immediately

Step 7: EmailConsumer receives message (milliseconds later)
  → Deserialise JSON
  → channel == EMAIL ✓ → proceed

Step 8: NotificationDispatchService.sendEmail()
  → CircuitBreaker: CLOSED → proceed
  → Retry wrapper starts
  → emailProvider.send("harmeet@example.com", "[URGENT] Notification", "Your OTP is 123456")
    → LogEmailProvider.send() → logs to console

Step 9: Update MySQL
  → UPDATE notification_logs SET status='SENT', provider='MOCK_EMAIL' WHERE idempotency_key='req-abc-001'

Result: Client can verify via GET /api/v1/notifications/harmeet@example.com
```

---

## 7. Database Design

### Entity Relationship

```
notification_logs          notification_templates      user_preferences
─────────────────          ──────────────────────      ────────────────
id (PK)                    id (PK)                     id (PK)
user_id                    name (UNIQUE)               user_id  ─┐
channel (EMAIL/SMS/PUSH)   body (TEXT)                 channel  ─┘ UNIQUE pair
status (PENDING/SENT/...)  channel                     enabled (default true)
provider                   created_at                  dnd_start
message (TEXT)             updated_at                  dnd_end
idempotency_key (UNIQUE)
failure_reason
created_at
updated_at
```

### JPA Mappings

JPA (Java Persistence API) is the bridge between Java objects and database rows.

- `@Entity` → this class maps to a DB table
- `@Table(name = "...")` → explicit table name
- `@Column(nullable = false)` → NOT NULL constraint
- `@Enumerated(EnumType.STRING)` → store enum as text ("EMAIL") not number (0)
- `@PrePersist` / `@PreUpdate` → auto-set `createdAt` / `updatedAt` timestamps
- `@GeneratedValue(strategy = GenerationType.IDENTITY)` → auto-increment primary key

---

## 8. Kafka Topics

| Topic | Group IDs listening | Purpose |
|-------|--------------------|---------| 
| `notification.high` | email-consumer-group, sms-consumer-group, push-consumer-group | OTPs, fraud alerts |
| `notification.medium` | (same 3) | Order updates, reminders |
| `notification.low` | (same 3) | Promotions, newsletters |

**Why do all 3 consumers listen to all 3 topics?**  
Because a HIGH priority SMS should be delivered by the SMS consumer, not the email consumer. The channel is part of the message payload, not the topic. Topics represent urgency, not channel.

**Partition key = `userId`**  
All messages for the same user land in the same partition. This guarantees order — if user A gets 3 OTPs, they arrive in the order they were sent.

---

## 9. Redis Keys

| Key Pattern | Example | TTL | Purpose |
|-------------|---------|-----|---------|
| `idempotency:<key>` | `idempotency:req-abc-001` | 24 hours | Prevents duplicate sends |
| `rate:<userId>:<CHANNEL>` | `rate:harmeet@example.com:EMAIL` | 60 seconds | Rate limit counter |

Inspect in redis-cli:
```bash
docker exec redis redis-cli KEYS "*"
docker exec redis redis-cli GET "idempotency:req-abc-001"
docker exec redis redis-cli TTL "rate:harmeet@example.com:EMAIL"
```

---

## 10. API Reference

### POST /api/v1/notifications

Send a notification.

**Request Body:**
```json
{
  "userId": "harmeet@example.com",
  "channel": "EMAIL",
  "priority": "HIGH",
  "message": "Your OTP is 123456",
  "idempotencyKey": "unique-request-id-001"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `userId` | string | ✅ | Email / phone / device token |
| `channel` | `EMAIL` \| `SMS` \| `PUSH` | ✅ | Delivery channel |
| `priority` | `HIGH` \| `MEDIUM` \| `LOW` | ✅ | Routes to correct Kafka topic |
| `message` | string | ❌ | Direct message body |
| `templateName` | string | ❌ | Name of a template from DB |
| `templateData` | object | ❌ | Key-value pairs for template `{placeholders}` |
| `idempotencyKey` | string | ❌ | Deduplicate key (auto-generated if missing) |

**Responses:**
| Code | Meaning |
|------|---------|
| `202 Accepted` | Notification queued successfully |
| `400 Bad Request` | Validation failed (missing required field) |
| `409 Conflict` | Duplicate idempotency key |
| `429 Too Many Requests` | Rate limit exceeded (> 5/min per channel) |

---

### GET /api/v1/notifications/{userId}

Get all notifications for a user.

```bash
curl http://localhost:8080/api/v1/notifications/harmeet@example.com
```

---

### GET /api/v1/notifications/status/{status}

Get all notifications with a specific status.

```bash
curl http://localhost:8080/api/v1/notifications/status/SENT
curl http://localhost:8080/api/v1/notifications/status/FAILED
curl http://localhost:8080/api/v1/notifications/status/PENDING
```

---

### GET /actuator/health

Full health check (MySQL, Redis, circuit breakers).

---

### GET /actuator/metrics/resilience4j.circuitbreaker.state?tag=name:email-cb

Circuit breaker state: `1.0` = CLOSED, `0.0` = OPEN, `0.5` = HALF-OPEN.

---

## 11. Configuration Reference

All configuration lives in `src/main/resources/application.yaml`.

```yaml
# ─── Providers (all disabled = mock/log mode by default) ─────────────────────
aws.ses.enabled: false          # set true + fill credentials for real email
twilio.enabled: false           # set true + fill credentials for real SMS
firebase.enabled: false         # set true + add service-account.json for real push

# ─── Rate Limiting ────────────────────────────────────────────────────────────
notification.rate-limit.max-per-minute: 5       # requests per user per channel
notification.rate-limit.window-seconds: 60      # sliding window size

# ─── Idempotency ──────────────────────────────────────────────────────────────
notification.idempotency.ttl-hours: 24          # how long to remember a key

# ─── Kafka Topics ─────────────────────────────────────────────────────────────
kafka.topics.high: notification.high
kafka.topics.medium: notification.medium
kafka.topics.low: notification.low

# ─── Circuit Breaker (per channel: email-cb, sms-cb, push-cb) ─────────────────
resilience4j.circuitbreaker.instances.email-cb:
  failure-rate-threshold: 50     # % of failures to trip
  sliding-window-size: 5         # evaluate last N calls
  wait-duration-in-open-state: 10s
```

---

## 12. How to Run Locally

### Prerequisites
- Java 17+ (`java -version`)
- Docker + Docker Compose (`docker --version`)
- Maven (or use included `mvnw`)

### Step 1: Start Docker containers
```bash
cd notification-service

# Stop system MySQL if running (or it conflicts on port 3306)
sudo systemctl stop mysql

# Start all 4 containers
docker compose up -d

# Verify all running
docker compose ps
```

### Step 2: Start the app
```bash
./mvnw spring-boot:run
```

Or run in background and tail logs:
```bash
./mvnw spring-boot:run > /tmp/app.log 2>&1 &
tail -f /tmp/app.log
```

### Step 3: Test it

**Send a notification:**
```bash
curl -X POST http://localhost:8080/api/v1/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "you@example.com",
    "channel": "EMAIL",
    "priority": "HIGH",
    "message": "Your OTP is 123456"
  }'
```

**Open Swagger UI:** http://localhost:8080/swagger-ui.html

**Check health:** http://localhost:8080/actuator/health

**View notification history:**
```bash
curl http://localhost:8080/api/v1/notifications/you@example.com
```

**Inspect Redis keys:**
```bash
docker exec redis redis-cli KEYS "*"
```

**View MySQL data:**
```bash
docker exec -it mysql mysql -u user -ppassword notification_service
SELECT id, user_id, channel, status, provider, created_at FROM notification_logs;
```

---

## 13. Key Design Decisions Explained

### Why separate topics for priority instead of a priority field in one topic?

Kafka processes messages in a topic sequentially within a partition. If LOW priority promotions flood `notification.all`, they'd delay HIGH priority OTPs. Separate topics (`high`/`medium`/`low`) let you allocate more consumers to high-priority processing independently.

### Why store the channel in the message, not in the topic?

A HIGH priority notification could be EMAIL, SMS, or PUSH. If you created 9 topics (high-email, high-sms, high-push, etc.), adding a new channel would multiply your topics. Storing channel in the payload is simpler and scales with new channels.

### Why all consumers listen to all 3 topics?

An OTP sent via SMS (HIGH priority) needs to be consumed by `SmsConsumer` from `notification.high`. If `SmsConsumer` only listened to `notification.medium`, that OTP would never be delivered. Each consumer type filters by its own channel and ignores the rest.

### Why `@ConditionalOnProperty` instead of `@ConditionalOnMissingBean`?

`@ConditionalOnMissingBean` is unreliable on `@Service` classes because Spring's component scan order is non-deterministic. `@ConditionalOnProperty` is evaluated before beans are created, so it always works correctly.

### Why `userId` as Kafka partition key?

Kafka only guarantees order *within a partition*. Using `userId` as the key ensures all messages for the same user go to the same partition → OTP1 always arrives before OTP2 for user A.

### Why Redis `INCR` for rate limiting instead of storing a list of timestamps?

`INCR` is a single atomic operation — no race conditions when 2 requests arrive simultaneously. It's O(1) and works across multiple app instances. Storing a list of timestamps would require locking and is much more complex.

### Why `NotificationDispatchService` instead of calling providers directly in consumers?

Before Day 7, consumers directly called providers. The problem: circuit breaker and retry annotations (`@CircuitBreaker`, `@Retry`) use Spring AOP (proxy pattern) — they only work when calling a Spring bean's method *from outside that bean*. If the consumer called `emailProvider.send()` inside a `try/catch`, you'd have to manually implement retry/CB logic. By delegating to `NotificationDispatchService`, the AOP proxy intercepts the call and handles resilience transparently.

---

## 14. Switching from Mock to Real Providers

All providers work in mock mode by default (logs to console). To use real services:

### Email — AWS SES

1. Create an AWS account, verify an email/domain in SES
2. Create an IAM user with `ses:SendEmail` permission, get Access Key + Secret
3. In `application.yaml`:
```yaml
aws:
  ses:
    enabled: true
    from-email: verified@yourdomain.com
  region: us-east-1        # your AWS region
  access-key: AKIA...
  secret-key: xxxxx
```

### SMS — Twilio

1. Sign up at twilio.com → get Account SID, Auth Token, and a phone number
2. In `application.yaml`:
```yaml
twilio:
  enabled: true
  account-sid: ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
  auth-token: your_auth_token
  from-number: "+15005550006"   # your Twilio number
```

### Push — Firebase FCM

1. Firebase Console → New Project → Cloud Messaging enabled
2. Project Settings → Service Accounts → Generate New Private Key → download JSON
3. Place the JSON at `src/main/resources/firebase-service-account.json`
4. In `application.yaml`:
```yaml
firebase:
  enabled: true
  service-account-path: classpath:firebase-service-account.json
```

---

## Days Remaining (Planned)

| Day | Topic |
|-----|-------|
| 8 | Template engine — render `{placeholder}` variables from `NotificationTemplate` DB records |
| 9 | SendGrid fallback — if AWS SES fails, switch to SendGrid automatically |
| 10 | UserPreference enforcement — respect `enabled=false` and DND hours |
| 11 | Prometheus + Grafana — visualise notification metrics on a dashboard |
| 12 | Unit + Integration tests — full test coverage with Testcontainers |
| 13 | Dockerize the app — build a Docker image for the service itself |
| 14 | CI/CD pipeline — GitHub Actions to build, test, and deploy automatically |

---

*Generated: May 2026 | Built with Spring Boot 3.5.14, Java 17, Apache Kafka 7.4.0, Redis 7.0, MySQL 8.0*
