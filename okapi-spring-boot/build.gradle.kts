plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.publish")
    // Mutation testing — opt-in only: ./gradlew :okapi-spring-boot:pitest -PenableMutationTesting=true
    alias(libs.plugins.pitest) apply false
}

description = "Spring Boot autoconfiguration for Okapi"

if (providers.gradleProperty("enableMutationTesting").orNull?.toBoolean() == true) {
    apply(plugin = libs.plugins.pitest.get().pluginId)
    configure<info.solidsoft.gradle.pitest.PitestPluginExtension> {
        pitestVersion.set(libs.versions.pitestTool.get())
        junit5PluginVersion.set(libs.versions.pitestJunit5Plugin.get())
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

// spring-boot-transaction is a Spring Boot 4.0+ artifact (3.x bundles TransactionAutoConfiguration
// in spring-boot-autoconfigure). The CI matrix override -PspringBootVersion=3.5.x rewrites every
// org.springframework.boot:* coordinate, so unconditionally declaring spring-boot-transaction makes
// it try to resolve a non-existent spring-boot-transaction:3.5.x. Gate on the resolved major.
val springBootMajorForTests = (
    providers.gradleProperty("springBootVersion").orNull ?: libs.versions.springBoot.get()
    ).substringBefore('.').toInt()

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
    // TransactionAutoConfiguration: in Spring Boot 4.0+ it lives in its own spring-boot-transaction
    // module; in 3.x it ships inside spring-boot-autoconfigure (already on the test classpath).
    // TransactionTemplateHijackProofTest resolves whichever FQCN is present, so we only need the
    // extra dependency on 4.x.
    if (springBootMajorForTests >= 4) {
        testImplementation(libs.springBootTransaction)
    }
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
