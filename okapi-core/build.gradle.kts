plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.publish")
}

description = "Core outbox abstractions and processing engine"

dependencies {
    implementation(libs.slf4jApi)
    testImplementation(libs.kotestRunnerJunit5)
    testImplementation(libs.kotestAssertionsCore)
    // logback (not slf4j-simple) at test scope so tests can attach a ListAppender to capture
    // log events from production code. Logback is the only SLF4J binding on the test classpath
    // (avoids the multiple-bindings warning); production code stays free of any logging backend.
    testImplementation(libs.logbackClassic)
}
