plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    implementation(project(":okapi-core"))

    compileOnly(libs.exposedCore)
    compileOnly(libs.exposedJdbc)
    compileOnly(libs.exposedJson)
    compileOnly(libs.exposedJavaTime)

    implementation(libs.jacksonModuleKotlin)
    implementation(libs.jacksonDatatypeJsr310)

    compileOnly(libs.liquibaseCore)

    testImplementation(libs.kotestRunnerJunit5)
    testImplementation(libs.kotestAssertionsCore)
    testImplementation(libs.testcontainersMysql)
    testImplementation(libs.mysql)
    testImplementation(libs.exposedCore)
    testImplementation(libs.exposedJdbc)
    testImplementation(libs.exposedJson)
    testImplementation(libs.exposedJavaTime)
    testImplementation(libs.liquibaseCore)
    testImplementation(libs.wiremock)
    testImplementation(project(":okapi-http"))
}
