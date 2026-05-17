plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.publish")
    // Mutation testing — opt-in only: ./gradlew :okapi-spring-boot:pitest -PenableMutationTesting=true
    id("info.solidsoft.pitest") version "1.19.0" apply false
}

description = "Spring Boot autoconfiguration for Okapi"

if (providers.gradleProperty("enableMutationTesting").orNull?.toBoolean() == true) {
    apply(plugin = "info.solidsoft.pitest")
    configure<info.solidsoft.gradle.pitest.PitestPluginExtension> {
        pitestVersion.set("1.17.0")
        junit5PluginVersion.set("1.2.1")
        targetClasses.set(
            listOf(
                "com.softwaremill.okapi.springboot.OutboxAutoConfiguration*",
                "com.softwaremill.okapi.springboot.OutboxProcessorScheduler*",
                "com.softwaremill.okapi.springboot.OutboxPurgerScheduler*",
                "com.softwaremill.okapi.springboot.OkapiProperties*",
                "com.softwaremill.okapi.springboot.SpringTransactionRunner*",
            ),
        )
        targetTests.set(listOf("com.softwaremill.okapi.springboot.*"))
        excludedTestClasses.set(
            listOf(
                // Postgres testcontainer-based tests are too heavy per-mutation
                "com.softwaremill.okapi.springboot.OutboxMysqlEndToEndTest",
            ),
        )
        threads.set(4)
        outputFormats.set(listOf("HTML", "XML"))
        timestampedReports.set(false)
    }
}

dependencies {
    implementation(project(":okapi-core"))

    compileOnly(libs.springContext)
    compileOnly(libs.springTx)
    compileOnly(libs.springJdbc)
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
    testImplementation(libs.h2)
    testImplementation(libs.springContext)
    testImplementation(libs.springTx)
    testImplementation(libs.springJdbc)
    testImplementation(libs.springBootAutoconfigure)
    testImplementation(libs.springBootTest)
    testImplementation(libs.assertjCore)
    testImplementation(project(":okapi-postgres"))
    testImplementation(project(":okapi-mysql"))
    testImplementation(project(":okapi-http"))
    testImplementation(libs.liquibaseCore)
    testImplementation(libs.testcontainersPostgresql)
    testImplementation(libs.postgresql)
    testImplementation(libs.testcontainersMysql)
    testImplementation(libs.mysql)
    testImplementation(libs.wiremock)
    testImplementation(project(":okapi-micrometer"))
    testImplementation(libs.micrometerCore)
    // Brings in the metrics auto-config jar so @AutoConfigureAfter targets are resolvable in tests.
    testImplementation(libs.springBootStarterActuator)
    // TransactionAutoConfiguration lives here in Spring Boot 4.0+ (was in spring-boot-autoconfigure
    // in 3.x). TransactionTemplateHijackProofTest needs it on the classpath to verify the
    // factory's behaviour against Boot's auto-created TransactionTemplate bean.
    testImplementation(libs.springBootTransaction)
    // Logback's ListAppender is used to capture and assert WARN-level log output (e.g. the
    // LiquibaseDisabledNotice breadcrumb + our PTM↔DS validation cannot-verify WARN) — slf4j-simple
    // does not provide an introspectable appender.
    testImplementation(libs.logbackClassic)
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
