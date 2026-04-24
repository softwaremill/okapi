plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.publish")
}

description = "Exposed ORM integration — ConnectionProvider, TransactionRunner, TransactionContextValidator"

dependencies {
    api(project(":okapi-core"))
    implementation(libs.exposedJdbc)

    testImplementation(libs.kotestRunnerJunit5)
    testImplementation(libs.kotestAssertionsCore)
    testImplementation(libs.h2)
}
