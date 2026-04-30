plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.jmh)
}

dependencies {
    // Okapi modules under measurement
    jmh(project(":okapi-core"))
    jmh(project(":okapi-postgres"))
    jmh(project(":okapi-kafka"))
    jmh(project(":okapi-http"))

    // Testcontainers — real Postgres + real Kafka for end-to-end throughput
    jmh(libs.testcontainersPostgresql)
    jmh(libs.testcontainersKafka)

    // DB driver + schema
    jmh(libs.postgresql)
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
    jvmArgs = listOf("-Xms2g", "-Xmx2g", "-XX:+UseG1GC")
}

// ktlint should not lint JMH-generated sources.
ktlint {
    filter {
        exclude { it.file.path.contains("/build/") || it.file.path.contains("/generated/") }
    }
}
