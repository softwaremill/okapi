plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.publish")
}

dependencies {
    implementation(libs.slf4jApi)
    testImplementation(libs.kotestRunnerJunit5)
    testImplementation(libs.kotestAssertionsCore)
    testRuntimeOnly(libs.slf4jSimple)
}
