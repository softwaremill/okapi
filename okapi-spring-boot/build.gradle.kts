plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    implementation(project(":okapi-core"))

    compileOnly(libs.springContext)
    compileOnly(libs.springTx)
    compileOnly(libs.springBootAutoconfigure)
    compileOnly(project(":okapi-postgres"))
    compileOnly(project(":okapi-mysql"))
    compileOnly(libs.liquibaseCore)

    testImplementation(libs.kotestRunnerJunit5)
    testImplementation(libs.kotestAssertionsCore)
    testImplementation(libs.springContext)
    testImplementation(libs.springTx)
    testImplementation(libs.exposedCore)
    testImplementation(libs.springBootAutoconfigure)
    testImplementation(project(":okapi-postgres"))
    testImplementation(project(":okapi-mysql"))
    testImplementation(project(":okapi-http"))
    testImplementation(libs.exposedJdbc)
    testImplementation(libs.exposedJson)
    testImplementation(libs.exposedJavaTime)
    testImplementation(libs.liquibaseCore)
    testImplementation(libs.testcontainersPostgresql)
    testImplementation(libs.postgresql)
    testImplementation(libs.testcontainersMysql)
    testImplementation(libs.mysql)
    testImplementation(libs.wiremock)
}
