# Okapi

Kotlin library implementing the **transactional outbox pattern** — reliable message delivery alongside local database operations.

Messages are stored in a database table within the same transaction as your business operation, then asynchronously delivered to external transports (HTTP webhooks, Kafka, etc.). This guarantees at-least-once delivery without distributed transactions.

## Modules

| Module | Purpose |
|--------|---------|
| `okapi-core` | Transport/storage-agnostic orchestration, scheduling, retry policy |
| `okapi-postgres` | PostgreSQL storage via Exposed ORM (`FOR UPDATE SKIP LOCKED`) |
| `okapi-http` | HTTP webhook delivery (JDK HttpClient) |
| `okapi-kafka` | Kafka topic publishing |
| `okapi-spring-boot` | Spring Boot autoconfiguration |
| `okapi-bom` | Bill of Materials for version alignment |

## Compatibility

| Dependency | Supported Versions | Notes |
|---|---|---|
| Java | 21+ | Required |
| Spring Boot | 3.5.x, 4.0.x | `okapi-spring-boot` module |
| Kafka Clients | 3.9.x, 4.x | `okapi-kafka` module — you provide `kafka-clients` dependency |
| Exposed | 1.x | `okapi-postgres`, `okapi-mysql` modules — you provide Exposed |
| Docker | Required for tests | Testcontainers-based integration tests |

## Quick Start (Spring Boot)

```kotlin
// 1. Add dependencies
dependencies {
    implementation(platform("com.softwaremill.okapi:okapi-bom:$version"))
    implementation("com.softwaremill.okapi:okapi-core")
    implementation("com.softwaremill.okapi:okapi-postgres")
    implementation("com.softwaremill.okapi:okapi-http")
    implementation("com.softwaremill.okapi:okapi-spring-boot")
}

// 2. Provide a MessageDeliverer bean
@Bean
fun httpDeliverer(): MessageDeliverer =
    HttpMessageDeliverer(ServiceUrlResolver { "https://my-service.example.com" })

// 3. Publish inside a transaction
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
```

> **Note:** `okapi-kafka` requires you to add `org.apache.kafka:kafka-clients` to your project.
> `okapi-postgres`/`okapi-mysql` require Exposed ORM dependencies.
> Spring and Kafka versions are not forced by okapi — you control them.

Autoconfiguration handles scheduling, retries, and delivery automatically.

## Standalone Usage

```kotlin
val scheduler = OutboxScheduler(processor, transactionRunner = myTxRunner)
scheduler.start()
// ... publish messages ...
scheduler.stop()
```

## Build

```sh
./gradlew build          # Build all modules
./gradlew test           # Run tests
./gradlew ktlintFormat   # Format code (mandatory before committing)
```

Requires JDK 21. Tests use [Testcontainers](https://www.testcontainers.org/) (Docker required).

## License

[Apache 2.0](LICENSE)
