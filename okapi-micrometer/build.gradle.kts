plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.publish")
}

dependencies {
    implementation(project(":okapi-core"))
    implementation(libs.slf4jApi)
    compileOnly(libs.micrometerCore)

    testImplementation(libs.kotestRunnerJunit5)
    testImplementation(libs.kotestAssertionsCore)
    testImplementation(libs.micrometerCore)
    testImplementation(libs.micrometerTest)
    testRuntimeOnly(libs.slf4jSimple)
}
