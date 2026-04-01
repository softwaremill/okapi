plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    // Okapi modules under test
    testImplementation(project(":okapi-core"))
    testImplementation(project(":okapi-postgres"))
    testImplementation(project(":okapi-mysql"))
    testImplementation(project(":okapi-kafka"))
    testImplementation(project(":okapi-http"))
    testImplementation(project(":okapi-spring-boot"))

    // Test framework
    testImplementation(libs.kotestRunnerJunit5)
    testImplementation(libs.kotestAssertionsCore)

    // Testcontainers
    testImplementation(libs.testcontainersPostgresql)
    testImplementation(libs.testcontainersMysql)
    testImplementation(libs.testcontainersKafka)

    // DB drivers
    testImplementation(libs.postgresql)
    testImplementation(libs.mysql)

    // Exposed ORM (for transaction blocks and DB queries in tests)
    testImplementation(libs.exposedCore)
    testImplementation(libs.exposedJdbc)
    testImplementation(libs.exposedJson)
    testImplementation(libs.exposedJavaTime)

    // Liquibase (schema migrations in tests)
    testImplementation(libs.liquibaseCore)

    // Kafka clients (consumer verification in tests)
    testImplementation(libs.kafkaClients)

    // SLF4J for Testcontainers logging
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")

    // WireMock (HTTP E2E tests)
    testImplementation(libs.wiremock)

    // Spring (for E2E tests that may need Spring context)
    testImplementation(libs.springContext)
    testImplementation(libs.springTx)
    testImplementation(libs.springBootAutoconfigure)
}
