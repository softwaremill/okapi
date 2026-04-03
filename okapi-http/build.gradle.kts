plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.publish")
}

description = "HTTP message delivery for outbox entries"

dependencies {
    implementation(project(":okapi-core"))
    implementation(libs.jacksonModuleKotlin)
    implementation(libs.jacksonDatatypeJsr310)

    testImplementation(libs.kotestRunnerJunit5)
    testImplementation(libs.kotestAssertionsCore)
    testImplementation(libs.wiremock)
}
