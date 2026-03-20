plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    implementation(project(":okapi-core"))
    implementation(libs.jacksonModuleKotlin)
    implementation(libs.jacksonDatatypeJsr310)

    testImplementation(libs.kotestRunnerJunit5)
    testImplementation(libs.kotestAssertionsCore)
}
