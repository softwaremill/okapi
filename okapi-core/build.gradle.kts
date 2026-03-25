plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    implementation(libs.slf4jApi)
    testImplementation(libs.kotestRunnerJunit5)
    testImplementation(libs.kotestAssertionsCore)
}
