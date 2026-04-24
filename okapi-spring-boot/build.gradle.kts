plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.publish")
}

description = "Spring Boot autoconfiguration for Okapi"

dependencies {
    implementation(project(":okapi-core"))

    compileOnly(libs.springContext)
    compileOnly(libs.springTx)
    compileOnly(libs.springBootAutoconfigure)

    // Validation annotations for @ConfigurationProperties classes
    compileOnly(libs.springBootStarterValidation)

    // Optional store autoconfiguration — compileOnly + @ConditionalOnClass guards runtime absence
    compileOnly(project(":okapi-postgres"))
    compileOnly(project(":okapi-mysql"))
    compileOnly(libs.liquibaseCore)
    compileOnly(project(":okapi-micrometer"))
    compileOnly(libs.micrometerCore)

    testImplementation(libs.kotestRunnerJunit5)
    testImplementation(libs.kotestAssertionsCore)
    testImplementation(libs.springContext)
    testImplementation(libs.springTx)
    testImplementation(libs.springJdbc)
    testImplementation(libs.springBootAutoconfigure)
    testImplementation(libs.springBootTest)
    testImplementation(libs.assertjCore)
    testImplementation(project(":okapi-postgres"))
    testImplementation(project(":okapi-mysql"))
    testImplementation(project(":okapi-http"))
    testImplementation(libs.exposedCore)
    testImplementation(libs.exposedJdbc)
    testImplementation(libs.exposedJson)
    testImplementation(libs.exposedJavaTime)
    testImplementation(libs.liquibaseCore)
    testImplementation(libs.testcontainersPostgresql)
    testImplementation(libs.postgresql)
    testImplementation(libs.testcontainersMysql)
    testImplementation(libs.mysql)
    testImplementation(libs.wiremock)
    testImplementation(project(":okapi-micrometer"))
    testImplementation(libs.micrometerCore)
}

// CI version override: ./gradlew :okapi-spring-boot:test -PspringBootVersion=4.0.4 -PspringVersion=7.0.6
val springBootVersion: String? by project
val springVersion: String? by project

if (springBootVersion != null || springVersion != null) {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (springBootVersion != null && requested.group == "org.springframework.boot") {
                useVersion(springBootVersion!!)
            }
            if (springVersion != null && requested.group == "org.springframework") {
                useVersion(springVersion!!)
            }
        }
    }
}
