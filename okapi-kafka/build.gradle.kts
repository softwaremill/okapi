plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    implementation(project(":okapi-core"))
    implementation(libs.jacksonModuleKotlin)
    implementation(libs.jacksonDatatypeJsr310)
    compileOnly(libs.kafkaClients)

    testImplementation(libs.kafkaClients)
    testImplementation(libs.kotestRunnerJunit5)
    testImplementation(libs.kotestAssertionsCore)
}
