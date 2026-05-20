package com.softwaremill.okapi.springboot

import com.mysql.cj.jdbc.MysqlDataSource
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.mysql.MysqlOutboxStore
import com.softwaremill.okapi.postgres.PostgresOutboxStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import liquibase.integration.spring.SpringLiquibase
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.support.GenericApplicationContext
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource

/**
 * End-to-end check that [OutboxAutoConfiguration] runs okapi's Liquibase migrations against real
 * databases and writes its history into the dedicated tracking tables (issue #37).
 *
 * Unit tests in [LiquibaseAutoConfigurationTest] verify bean wiring and YAML property binding;
 * this test proves the setters actually flow through SpringLiquibase to real DDL — i.e. that
 * `okapi_databasechangelog` exists after startup and the host application's `databasechangelog`
 * stays untouched. Postgres and MySQL get equal coverage because they use different Liquibase
 * adapters and different DDL semantics.
 */
class LiquibaseE2ETest : FunSpec({

    val postgres = PostgreSQLContainer<Nothing>("postgres:16")
    val postgresSecondary = PostgreSQLContainer<Nothing>("postgres:16")
    val mysql = MySQLContainer<Nothing>("mysql:8.0")

    beforeSpec {
        postgres.start()
        postgresSecondary.start()
        mysql.start()
    }
    afterSpec {
        postgres.stop()
        postgresSecondary.stop()
        mysql.stop()
    }

    context("postgres") {
        fun dataSource(): DataSource = PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }

        fun resetSchema() {
            dataSource().connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("DROP SCHEMA public CASCADE")
                    stmt.execute("CREATE SCHEMA public")
                }
            }
        }

        fun listTables(ds: DataSource): Set<String> = ds.connection.use { conn ->
            conn.metaData.getTables(null, "public", "%", arrayOf("TABLE")).use { rs ->
                buildSet { while (rs.next()) add(rs.getString("TABLE_NAME").lowercase()) }
            }
        }

        // Hide MysqlOutboxStore from the classpath so that PostgresStoreConfiguration is the only
        // store factory that activates and `okapiPostgresLiquibase` is the only Liquibase bean
        // that registers (its `@ConditionalOnBean(PostgresOutboxStore)` gate matches the winner).
        // Without this filter the OutboxStore precedence in the test JVM is non-deterministic
        // between Postgres and MySQL, and these tests need to deterministically exercise the
        // Postgres path against a real Postgres DataSource. The dual-module coexistence (both
        // modules visible, only the matching Liquibase activates) is covered by
        // ["both okapi-postgres and okapi-mysql on classpath: exactly one okapi*Liquibase activates..."].
        fun runner(ds: DataSource) = ApplicationContextRunner()
            .withClassLoader(FilteredClassLoader(MysqlOutboxStore::class.java))
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java, OkapiLiquibaseAutoConfiguration::class.java))
            .withBean(MessageDeliverer::class.java, { stubDeliverer() })
            .withBean(DataSource::class.java, { ds })
            .withPropertyValues(
                "okapi.processor.enabled=false",
                "okapi.purger.enabled=false",
            )

        beforeEach { resetSchema() }

        test("both okapi-postgres and okapi-mysql on classpath: exactly one okapi*Liquibase activates against a real Postgres database") {
            // Regression test for issue #38 / KOJAK-80. Before the per-engine
            // @ConditionalOnBean(<X>OutboxStore) gate on each *LiquibaseConfiguration, both
            // `okapiPostgresLiquibase` and `okapiMysqlLiquibase` registered against the same
            // DataSource and the second-evaluated Liquibase failed at startup with a
            // duplicate-object error from the wrong-engine changelog
            // (e.g. ERROR: relation "idx_okapi_outbox_status_last_attempt" already exists).
            //
            // No FilteredClassLoader here — both `okapi-postgres` and `okapi-mysql` are visible
            // on the runtime classpath, mirroring a real consumer that pulls in both modules
            // (intentionally or transitively).
            val ds = dataSource()

            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java, OkapiLiquibaseAutoConfiguration::class.java))
                .withBean(MessageDeliverer::class.java, { stubDeliverer() })
                .withBean(DataSource::class.java, { ds })
                .withPropertyValues(
                    "okapi.processor.enabled=false",
                    "okapi.purger.enabled=false",
                )
                .run { ctx ->
                    ctx.startupFailure.shouldBeNull()
                    val activeLiquibase = listOf("okapiPostgresLiquibase", "okapiMysqlLiquibase")
                        .filter { ctx.containsBean(it) }
                    activeLiquibase.size shouldBe 1
                }
        }

        test("autoconfig creates okapi_databasechangelog and runs okapi migrations") {
            val ds = dataSource()

            runner(ds).run { ctx ->
                ctx.startupFailure.shouldBeNull()

                val tables = listTables(ds)
                tables shouldContain "okapi_databasechangelog"
                tables shouldContain "okapi_databasechangeloglock"
                tables shouldContain "okapi_outbox"
                tables shouldNotContain "outbox"
                tables shouldNotContain "databasechangelog"
                tables shouldNotContain "databasechangeloglock"
            }
        }

        test("custom changelog-table property creates the named table instead") {
            val ds = dataSource()

            runner(ds)
                .withPropertyValues(
                    "okapi.liquibase.changelog-table=my_outbox_changelog",
                    "okapi.liquibase.changelog-lock-table=my_outbox_changelog_lock",
                )
                .run { ctx ->
                    ctx.startupFailure.shouldBeNull()

                    val tables = listTables(ds)
                    tables shouldContain "my_outbox_changelog"
                    tables shouldContain "my_outbox_changelog_lock"
                    tables shouldContain "okapi_outbox"
                    tables shouldNotContain "okapi_databasechangelog"
                }
        }

        test("coexistence with the host application's own SpringLiquibase via Spring Boot autoconfig (issue #38 Mode 1)") {
            // High-fidelity test for issue #38: the host application uses Spring Boot's standard
            // `LiquibaseAutoConfiguration` + `spring.liquibase.change-log` to set up its own
            // SpringLiquibase. Okapi must run AFTER that autoconfig (`@AutoConfigureAfter`), so
            // Spring Boot's `@ConditionalOnMissingBean(SpringLiquibase)` sees its own bean register
            // first and does NOT skip itself. Otherwise okapi's bean shadows the host's silently.
            //
            // The previous version of this test pre-registered the user bean via withBean(...),
            // which bypasses autoconfig ordering entirely (the bean was always present before any
            // autoconfig ran) and therefore could not detect a missing or wrong @AutoConfigureAfter.
            //
            // Here we pull Spring Boot's real `LiquibaseAutoConfiguration` into the runner, so the
            // ordering contract is exercised end-to-end. On Spring Boot 4.0.x the autoconfig is
            // not on the classpath (Liquibase autoconfig was moved out of spring-boot-autoconfigure
            // and a dedicated `spring-boot-liquibase` artifact is not pulled into okapi-spring-boot
            // tests), so the test is skipped — the CI matrix's 3.5.x dimension provides the actual
            // coverage; the structural / reflection assertions in [LiquibaseAutoConfigurationTest]
            // are the regression net on 4.0.x.
            val springBootLiquibaseAutoConfig = resolveSpringBootClass(
                "org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration",
                "org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration",
            ) ?: return@test // skip on 4.0.x where Spring Boot Liquibase autoconfig is absent

            resetSchema() // ensure prior tests in this context didn't leave okapi_* tables
            val ds = dataSource()

            ApplicationContextRunner()
                .withClassLoader(FilteredClassLoader(MysqlOutboxStore::class.java))
                .withConfiguration(
                    AutoConfigurations.of(
                        OutboxAutoConfiguration::class.java,
                        OkapiLiquibaseAutoConfiguration::class.java,
                        springBootLiquibaseAutoConfig,
                    ),
                )
                .withBean(MessageDeliverer::class.java, { stubDeliverer() })
                .withBean(DataSource::class.java, { ds })
                .withPropertyValues(
                    "okapi.processor.enabled=false",
                    "okapi.purger.enabled=false",
                    "spring.liquibase.change-log=classpath:/com/softwaremill/okapi/springboot/test-app-changelog.xml",
                )
                .run { ctx ->
                    ctx.startupFailure.shouldBeNull()

                    // Spring Boot's autoconfig registered its bean (name "liquibase") AND
                    // okapi registered its bean ("okapiPostgresLiquibase"). Both ran on the same
                    // DataSource. If @AutoConfigureAfter were missing, Spring Boot's @ConditionalOnMissingBean
                    // (by type) would see okapi's bean first and silently skip its own — `liquibase`
                    // would be missing here.
                    val liquibaseBeans = ctx.getBeansOfType(SpringLiquibase::class.java)
                    liquibaseBeans.keys shouldContain "liquibase"
                    liquibaseBeans.keys shouldContain "okapiPostgresLiquibase"

                    val tables = listTables(ds)
                    // Okapi's tables (dedicated tracking + outbox)
                    tables shouldContain "okapi_databasechangelog"
                    tables shouldContain "okapi_databasechangeloglock"
                    tables shouldContain "okapi_outbox"
                    // App's tables (Spring Boot Liquibase's default tracking + the table the
                    // test changelog creates)
                    tables shouldContain "databasechangelog"
                    tables shouldContain "databasechangeloglock"
                    tables shouldContain "app_table_marker"
                }
        }

        test("multi-datasource: okapi targets the DataSource named by `okapi.datasource-qualifier`") {
            // Two PostgreSQL instances. The primary holds unrelated application data; okapi must
            // run its migrations against the secondary, named via `okapi.datasource-qualifier`.
            // `DataSourceQualifierAutoConfigurationTest` covers the resolver semantics with stubs;
            // this E2E verifies the autoconfigured `okapiPostgresLiquibase` bean actually targets
            // the qualified DataSource on real DDL.
            val primaryDs = dataSource()
            val secondaryDs = PGSimpleDataSource().apply {
                setURL(postgresSecondary.jdbcUrl)
                user = postgresSecondary.username
                password = postgresSecondary.password
            }

            // beforeEach resets the primary; reset the secondary too so repeated runs stay clean.
            secondaryDs.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("DROP SCHEMA public CASCADE")
                    stmt.execute("CREATE SCHEMA public")
                }
            }

            ApplicationContextRunner()
                .withClassLoader(FilteredClassLoader(MysqlOutboxStore::class.java))
                .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java, OkapiLiquibaseAutoConfiguration::class.java))
                .withBean(MessageDeliverer::class.java, { stubDeliverer() })
                .withInitializer { context ->
                    val bd = BeanDefinitionBuilder.genericBeanDefinition(DataSource::class.java) { primaryDs }
                        .beanDefinition
                    bd.isPrimary = true
                    (context as GenericApplicationContext).registerBeanDefinition("primaryDs", bd)
                }
                .withBean("secondaryDs", DataSource::class.java, { secondaryDs })
                .withPropertyValues(
                    "okapi.processor.enabled=false",
                    "okapi.purger.enabled=false",
                    "okapi.datasource-qualifier=secondaryDs",
                )
                .run { ctx ->
                    ctx.startupFailure.shouldBeNull()

                    val secondaryTables = listTables(secondaryDs)
                    secondaryTables shouldContain "okapi_outbox"
                    secondaryTables shouldContain "okapi_databasechangelog"
                    secondaryTables shouldContain "okapi_databasechangeloglock"

                    val primaryTables = listTables(primaryDs)
                    primaryTables shouldNotContain "okapi_outbox"
                    primaryTables shouldNotContain "okapi_databasechangelog"
                    primaryTables shouldNotContain "okapi_databasechangeloglock"
                }
        }
    }

    context("mysql") {
        fun dataSource(): DataSource = MysqlDataSource().apply {
            setURL(mysql.jdbcUrl)
            user = mysql.username
            setPassword(mysql.password)
        }

        fun resetSchema() {
            dataSource().connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("SET FOREIGN_KEY_CHECKS = 0")
                    val tables = mutableListOf<String>()
                    conn.metaData.getTables(mysql.databaseName, null, "%", arrayOf("TABLE")).use { rs ->
                        while (rs.next()) tables.add(rs.getString("TABLE_NAME"))
                    }
                    tables.forEach { stmt.execute("DROP TABLE IF EXISTS `$it`") }
                    stmt.execute("SET FOREIGN_KEY_CHECKS = 1")
                }
            }
        }

        fun listTables(ds: DataSource): Set<String> = ds.connection.use { conn ->
            conn.metaData.getTables(mysql.databaseName, null, "%", arrayOf("TABLE")).use { rs ->
                buildSet { while (rs.next()) add(rs.getString("TABLE_NAME").lowercase()) }
            }
        }

        // Hide PostgresOutboxStore from the classpath: both `okapi-postgres` and `okapi-mysql` are
        // on the test classpath, and PostgresStoreConfiguration would otherwise activate first and
        // try to run Postgres-specific Liquibase changesets against this MySQL container.
        fun runner(ds: DataSource) = ApplicationContextRunner()
            .withClassLoader(FilteredClassLoader(PostgresOutboxStore::class.java))
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java, OkapiLiquibaseAutoConfiguration::class.java))
            .withBean(MessageDeliverer::class.java, { stubDeliverer() })
            .withBean(DataSource::class.java, { ds })
            .withPropertyValues(
                "okapi.processor.enabled=false",
                "okapi.purger.enabled=false",
            )

        beforeEach { resetSchema() }

        test("autoconfig creates okapi_databasechangelog and runs okapi migrations") {
            val ds = dataSource()

            runner(ds).run { ctx ->
                ctx.startupFailure.shouldBeNull()

                val tables = listTables(ds)
                tables shouldContain "okapi_databasechangelog"
                tables shouldContain "okapi_databasechangeloglock"
                tables shouldContain "okapi_outbox"
                tables shouldNotContain "outbox"
                tables shouldNotContain "databasechangelog"
                tables shouldNotContain "databasechangeloglock"
            }
        }

        test("custom changelog-table property creates the named table instead") {
            val ds = dataSource()

            runner(ds)
                .withPropertyValues(
                    "okapi.liquibase.changelog-table=my_outbox_changelog",
                    "okapi.liquibase.changelog-lock-table=my_outbox_changelog_lock",
                )
                .run { ctx ->
                    ctx.startupFailure.shouldBeNull()

                    val tables = listTables(ds)
                    tables shouldContain "my_outbox_changelog"
                    tables shouldContain "my_outbox_changelog_lock"
                    tables shouldContain "okapi_outbox"
                    tables shouldNotContain "okapi_databasechangelog"
                }
        }

        test("coexistence with the host application's own SpringLiquibase via Spring Boot autoconfig (issue #38 Mode 1)") {
            // Mirror of the Postgres coexistence test on MySQL — the bug class is not adapter-
            // specific, but Liquibase's MySQL adapter has its own quirks (lock-table charset,
            // FOREIGN_KEY_CHECKS) so we exercise the same scenario against a real MySQL container.
            val springBootLiquibaseAutoConfig = resolveSpringBootClass(
                "org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration",
                "org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration",
            ) ?: return@test // skip on Spring Boot 4.0.x where the autoconfig is absent

            resetSchema()
            val ds = dataSource()

            ApplicationContextRunner()
                .withClassLoader(FilteredClassLoader(PostgresOutboxStore::class.java))
                .withConfiguration(
                    AutoConfigurations.of(
                        OutboxAutoConfiguration::class.java,
                        OkapiLiquibaseAutoConfiguration::class.java,
                        springBootLiquibaseAutoConfig,
                    ),
                )
                .withBean(MessageDeliverer::class.java, { stubDeliverer() })
                .withBean(DataSource::class.java, { ds })
                .withPropertyValues(
                    "okapi.processor.enabled=false",
                    "okapi.purger.enabled=false",
                    "spring.liquibase.change-log=classpath:/com/softwaremill/okapi/springboot/test-app-changelog.xml",
                )
                .run { ctx ->
                    ctx.startupFailure.shouldBeNull()

                    val liquibaseBeans = ctx.getBeansOfType(SpringLiquibase::class.java)
                    liquibaseBeans.keys shouldContain "liquibase"
                    liquibaseBeans.keys shouldContain "okapiMysqlLiquibase"

                    val tables = listTables(ds)
                    tables shouldContain "okapi_databasechangelog"
                    tables shouldContain "okapi_databasechangeloglock"
                    tables shouldContain "okapi_outbox"
                    tables shouldContain "databasechangelog"
                    tables shouldContain "databasechangeloglock"
                    tables shouldContain "app_table_marker"
                }
        }
    }
})

// Loads the first Spring Boot autoconfig FQCN that resolves on the runtime classpath, or null
// if none do. Used to bridge the 3.5.x (`org.springframework.boot.autoconfigure.*`) and 4.0.x
// (`org.springframework.boot.<module>.autoconfigure.*`) package layouts in tests that pull in
// Spring Boot's own auto-configurations alongside okapi's.
private fun resolveSpringBootClass(vararg candidateFqcns: String): Class<*>? {
    val classLoader = LiquibaseE2ETest::class.java.classLoader
    return candidateFqcns.firstNotNullOfOrNull { fqcn ->
        try {
            Class.forName(fqcn, false, classLoader)
        } catch (_: ClassNotFoundException) {
            null
        }
    }
}
