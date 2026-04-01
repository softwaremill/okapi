plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.publish")
}

description = "Core outbox abstractions and processing engine"

dependencies {
    implementation(libs.slf4jApi)
    compileOnly(libs.exposedJdbc)
    testImplementation(libs.kotestRunnerJunit5)
    testImplementation(libs.kotestAssertionsCore)
    testImplementation(libs.exposedJdbc)
    testImplementation("com.h2database:h2:2.3.232")
    testRuntimeOnly(libs.slf4jSimple)
}
