plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.jmh)
}

dependencies {
    // Okapi modules under measurement
    jmh(project(":okapi-core"))
    jmh(project(":okapi-postgres"))
    jmh(project(":okapi-mysql"))
    jmh(project(":okapi-kafka"))
    jmh(project(":okapi-http"))

    // Testcontainers — real Postgres + real MySQL + real Kafka for end-to-end throughput
    jmh(libs.testcontainersPostgresql)
    jmh(libs.testcontainersMysql)
    jmh(libs.testcontainersKafka)

    // DB driver + schema
    jmh(libs.postgresql)
    jmh(libs.mysql)
    jmh(libs.liquibaseCore)

    // Kafka clients (provides MockProducer for microbenchmarks)
    jmh(libs.kafkaClients)

    // WireMock for HTTP target
    jmh(libs.wiremock)

    // SLF4J for Testcontainers/Kafka logging
    jmh(libs.slf4jSimple)

    // JMH core + annotation processor
    jmh(libs.jmhCore)
    jmh(libs.jmhGeneratorAnnprocess)
    jmhAnnotationProcessor(libs.jmhGeneratorAnnprocess)
}

jmh {
    fork = 2
    warmupIterations = 3
    warmup = "10s"
    iterations = 5
    timeOnIteration = "30s"
    resultFormat = "JSON"
    resultsFile = layout.buildDirectory.file("reports/jmh/results.json")
    jvmArgs = listOf(
        // Throughput-mode microbenchmarks call deliver() in a tight loop and re-deserialize
        // KafkaDeliveryInfo via Jackson + Kotlin reflection per invocation; with -Xmx2g this
        // OOMs within the first measurement iteration. 8g leaves room for GC under sustained
        // allocation pressure without skewing the benchmark with promotion stalls.
        "-Xms8g",
        "-Xmx8g",
        "-XX:+UseG1GC",
        // okapi-postgres.jar and the fat JMH jar both end up on the classpath; both carry
        // the Liquibase changelog. Liquibase 4.x treats this as an error by default. The
        // files are identical (same jar source on the classpath twice), so WARN is safe.
        "-Dliquibase.duplicateFileMode=WARN",
    )
}

// ktlint should not lint JMH-generated sources.
ktlint {
    filter {
        exclude { it.file.path.contains("/build/") || it.file.path.contains("/generated/") }
    }
}
