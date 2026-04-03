plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.publish")
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
}
