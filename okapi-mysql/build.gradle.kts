plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.publish")
}

description = "MySQL outbox store using plain JDBC"

dependencies {
    implementation(project(":okapi-core"))
    implementation(libs.slf4jApi)

    compileOnly(libs.liquibaseCore)

    testImplementation(libs.kotestRunnerJunit5)
    testImplementation(libs.kotestAssertionsCore)
    testImplementation(libs.testcontainersMysql)
    testImplementation(libs.mysql)
}
