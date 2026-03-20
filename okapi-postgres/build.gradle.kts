plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    implementation(project(":okapi-core"))

    implementation(libs.exposedCore)
    implementation(libs.exposedJdbc)
    implementation(libs.exposedJson)
    implementation(libs.exposedJavaTime)

    implementation(libs.jacksonModuleKotlin)
    implementation(libs.jacksonDatatypeJsr310)

    implementation(libs.liquibaseCore)

    testImplementation(libs.kotestRunnerJunit5)
    testImplementation(libs.kotestAssertionsCore)
    testImplementation(libs.testcontainersPostgresql)
    testImplementation(libs.postgresql)
}
