plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    implementation(project(":okapi-core"))

    // Spring provided by the consuming application — compileOnly keeps this module free of version lock-in
    compileOnly(libs.springContext)
    compileOnly(libs.springTx)

    // Spring Boot autoconfiguration support — compileOnly so we don't force Spring Boot on consumers
    compileOnly(libs.springBootAutoconfigure)

    // Optional postgres store autoconfiguration — compileOnly + @ConditionalOnClass guards runtime absence
    compileOnly(project(":okapi-postgres"))

    // Optional Liquibase migration support — compileOnly, activated only when liquibase-core is on the runtime classpath
    compileOnly(libs.liquibaseCore)

    testImplementation(libs.kotestRunnerJunit5)
    testImplementation(libs.kotestAssertionsCore)
    testImplementation(libs.springContext)
    testImplementation(libs.springTx)
    testImplementation(libs.exposedCore)
    testImplementation(libs.springBootAutoconfigure)
    testImplementation(project(":okapi-postgres"))

    // E2E test dependencies
    testImplementation(project(":okapi-http"))
    testImplementation(libs.exposedJdbc)
    testImplementation(libs.exposedJson)
    testImplementation(libs.exposedJavaTime)
    testImplementation(libs.liquibaseCore)
    testImplementation(libs.testcontainersPostgresql)
    testImplementation(libs.postgresql)
    testImplementation(libs.wiremock)
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
