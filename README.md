# Okapi

[![Ideas, suggestions, problems, questions](https://img.shields.io/badge/Discourse-ask%20question-blue)](https://softwaremill.community/c/open-source/11)
[![CI](https://github.com/softwaremill/okapi/workflows/CI/badge.svg)](https://github.com/softwaremill/okapi/actions?query=workflow%3A%22CI%22)
[![Kotlin](https://img.shields.io/badge/dynamic/toml?url=https%3A%2F%2Fraw.githubusercontent.com%2Fsoftwaremill%2Fokapi%2Frefs%2Fheads%2Fmain%2Fgradle%2Flibs.versions.toml&query=%24.versions.kotlin&logo=kotlin&label=kotlin&color=blue)](https://kotlinlang.org)
[![JVM](https://img.shields.io/badge/JVM-21-orange.svg?logo=openjdk)](https://www.java.com)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

Kotlin library implementing the **transactional outbox pattern** — reliable message delivery alongside local database operations.

Messages are stored in a database table within the same transaction as your business operation, then asynchronously delivered to external transports (HTTP webhooks, Kafka). This guarantees **at-least-once delivery** without distributed transactions.

## Quick Start (Spring Boot)

Add dependencies using the BOM for version alignment:

```kotlin
dependencies {
    implementation(platform("com.softwaremill.okapi:okapi-bom:$okapiVersion"))
    implementation("com.softwaremill.okapi:okapi-core")
    implementation("com.softwaremill.okapi:okapi-postgres")
    implementation("com.softwaremill.okapi:okapi-http")
    implementation("com.softwaremill.okapi:okapi-spring-boot")
}
```

Provide a `MessageDeliverer` bean — this tells okapi how to deliver messages.
`ServiceUrlResolver` maps the logical service name (set per message) to a base URL:

```kotlin
@Bean
fun httpDeliverer(): HttpMessageDeliverer =
    HttpMessageDeliverer(ServiceUrlResolver { serviceName ->
        when (serviceName) {
            "notification-service" -> "https://notifications.example.com"
            else -> error("Unknown service: $serviceName")
        }
    })
```

Publish inside any `@Transactional` method — inject `SpringOutboxPublisher` via constructor:

```kotlin
@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val springOutboxPublisher: SpringOutboxPublisher
) {
    @Transactional
    fun placeOrder(order: Order) {
        orderRepository.save(order)
        springOutboxPublisher.publish(
            OutboxMessage("order.created", order.toJson()),
            httpDeliveryInfo {
                serviceName = "notification-service"
                endpointPath = "/webhooks/orders"
            }
        )
    }
}
```

Autoconfiguration handles scheduling, retries, and delivery automatically. For Micrometer metrics, also add `okapi-micrometer` — see [Observability](#observability).

**Using Kafka instead of HTTP?** Swap the deliverer bean and delivery info:

```kotlin
@Bean
fun kafkaDeliverer(producer: KafkaProducer<String, String>): KafkaMessageDeliverer =
    KafkaMessageDeliverer(producer)
```
```kotlin
springOutboxPublisher.publish(
    OutboxMessage("order.created", order.toJson()),
    kafkaDeliveryInfo { topic = "order-events" }
)
```

**Using MySQL instead of PostgreSQL?** Replace `okapi-postgres` with `okapi-mysql` in your dependencies — no code changes needed.

> **Note:** `okapi-postgres` and `okapi-mysql` require Exposed ORM dependencies in your project.
> Spring and Kafka versions are not forced by okapi — you control them.

## How It Works

Okapi implements the [transactional outbox pattern](https://softwaremill.com/microservices-101/) (see also: [microservices.io description](https://microservices.io/patterns/data/transactional-outbox.html)):

1. Your application writes an `OutboxMessage` to the outbox table **in the same database transaction** as your business operation
2. A background `OutboxScheduler` polls for pending messages and delivers them to the configured transport (HTTP, Kafka)
3. Failed deliveries are retried according to a configurable `RetryPolicy` (max attempts, backoff)

**Delivery guarantees:**

- **At-least-once delivery** — okapi guarantees every message will be delivered, but duplicates are possible (e.g., after a crash between delivery and status update). Consumers should handle idempotency, for example by checking the `OutboxId` returned by `publish()`.
- **Concurrent processing** — multiple processors can run in parallel using `FOR UPDATE SKIP LOCKED`, so messages are never processed twice simultaneously.
- **Delivery result classification** — each transport classifies errors as `Success`, `RetriableFailure`, or `PermanentFailure`. For example, HTTP 429 is retriable while HTTP 400 is permanent.

## Observability

Add `okapi-micrometer` alongside `okapi-spring-boot` (from the Quick Start above) to get Micrometer metrics:

```kotlin
implementation("com.softwaremill.okapi:okapi-micrometer")
```

With Spring Boot Actuator and a Prometheus registry (`micrometer-registry-prometheus`) on the classpath, metrics are automatically exposed on `/actuator/prometheus`. They are also visible via `/actuator/metrics`.

| Metric | Type | Description |
|--------|------|-------------|
| `okapi.entries.delivered` | Counter | Successfully delivered entries |
| `okapi.entries.retry.scheduled` | Counter | Failed attempts rescheduled for retry |
| `okapi.entries.failed` | Counter | Permanently failed entries |
| `okapi.batch.duration` | Timer | Processing time per batch |
| `okapi.entries.count` | Gauge | Current entry count (tag: `status=pending\|delivered\|failed`) |
| `okapi.entries.lag.seconds` | Gauge | Age of oldest entry in seconds (tag: `status`) |

### Multi-instance deployments

Counters and timers (`okapi.entries.delivered`, `okapi.entries.retry.scheduled`, `okapi.entries.failed`, `okapi.batch.duration`) report work performed by **each instance** — aggregate with `sum`:

```promql
sum(rate(okapi_entries_delivered_total[5m]))
```

Gauges (`okapi.entries.count`, `okapi.entries.lag.seconds`) reflect the **shared outbox state** and are reported identically by every instance. Aggregate with `max by (status)`, not `sum`:

```promql
max by (status) (okapi_entries_count)
```

Polling cost per instance is `2 queries / okapi.metrics.refresh-interval` (default `2 queries / 15s`).

### Without Spring Boot

`okapi-micrometer` has no Spring dependency. Construct the beans manually and pass a `MeterRegistry`. `MicrometerOutboxMetrics` requires a `TransactionRunner` for Exposed-backed stores — see the class KDoc.

For periodic gauge refresh, use the framework-agnostic `OutboxMetricsRefresher` (single daemon thread):

```kotlin
val listener = MicrometerOutboxListener(meterRegistry)
val metrics = MicrometerOutboxMetrics(store, meterRegistry, transactionRunner)

val refresher = OutboxMetricsRefresher(metrics, Duration.ofSeconds(15))
refresher.start()
// on application shutdown:
refresher.close()
```

Or call `metrics.refresh()` from your own scheduler (Ktor coroutine, `ScheduledExecutorService`, etc.) — `refresh()` is thread-safe.

### Custom listener

Implement `OutboxProcessorListener` to react to delivery events (logging, alerting, custom metrics). `OutboxProcessor` accepts a single listener; to combine multiple, implement a composite that delegates to each.

## Modules

```mermaid
graph BT
    PG[okapi-postgres] --> CORE[okapi-core]
    MY[okapi-mysql] --> CORE
    HTTP[okapi-http] --> CORE
    KAFKA[okapi-kafka] --> CORE
    MICRO[okapi-micrometer] --> CORE
    SPRING[okapi-spring-boot] --> CORE
    SPRING -.->|compileOnly| PG
    SPRING -.->|compileOnly| MY
    SPRING -.->|compileOnly| MICRO
    BOM[okapi-bom]

    style CORE fill:#4a9eff,color:#fff
    style BOM fill:#888,color:#fff
```

| Module | Purpose |
|--------|---------|
| `okapi-core` | Transport/storage-agnostic orchestration, scheduling, retry policy |
| `okapi-postgres` | PostgreSQL storage via Exposed ORM (`FOR UPDATE SKIP LOCKED`) |
| `okapi-mysql` | MySQL 8+ storage via Exposed ORM |
| `okapi-http` | HTTP webhook delivery (JDK HttpClient) |
| `okapi-kafka` | Kafka topic publishing |
| `okapi-micrometer` | Micrometer metrics (counters, timers, gauges) |
| `okapi-spring-boot` | Spring Boot autoconfiguration (auto-detects store, transports, and metrics) |
| `okapi-bom` | Bill of Materials for version alignment |

## Compatibility

| Dependency | Supported Versions | Notes |
|---|---|---|
| Java | 21+ | Required |
| Spring Boot | 3.5.x, 4.0.x | `okapi-spring-boot` module |
| Kafka Clients | 3.9.x, 4.x | `okapi-kafka` — you provide `kafka-clients` |
| Exposed | 1.x | `okapi-postgres`, `okapi-mysql` — you provide Exposed |

## Build

```sh
./gradlew build          # Build all modules
./gradlew test           # Run tests (Docker required — Testcontainers)
./gradlew ktlintFormat   # Format code
```

Requires JDK 21.

## Contributing

All suggestions welcome :)

To compile and test, run:

```sh
./gradlew build
./gradlew ktlintFormat   # Mandatory before committing
```

See the list of [issues](https://github.com/softwaremill/okapi/issues) and pick one! Or report your own.

If you are having doubts on the _why_ or _how_ something works, don't hesitate to ask a question on [Discourse](https://softwaremill.community/c/open-source/11) or via GitHub. This probably means that the documentation or code is unclear and can be improved for the benefit of all.

Tests use [Testcontainers](https://www.testcontainers.org/) — Docker must be running.

When you have a PR ready, take a look at our ["How to prepare a good PR" guide](https://softwaremill.community/t/how-to-prepare-a-good-pr-to-a-library/448). Thanks! :)

## Project sponsor

We offer commercial development services. [Contact us](https://softwaremill.com) to learn more about us!

## Copyright

Copyright (C) 2026 SoftwareMill [https://softwaremill.com](https://softwaremill.com).
