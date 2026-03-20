plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    testImplementation(libs.kotestRunnerJunit5)
    testImplementation(libs.kotestAssertionsCore)
}
