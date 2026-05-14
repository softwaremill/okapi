package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.mysql.MysqlOutboxStore
import com.softwaremill.okapi.postgres.PostgresOutboxStore
import liquibase.integration.spring.SpringLiquibase
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

private val LIQUIBASE_DISABLED_LOGGER = LoggerFactory.getLogger("com.softwaremill.okapi.springboot.OkapiLiquibaseAutoConfiguration")

/**
 * Auto-configures okapi's bundled Liquibase migrations.
 *
 * **Why a separate auto-config from [OutboxAutoConfiguration]:** the engine-specific Liquibase
 * configs must activate based on which `OutboxStore` bean actually won precedence (Postgres takes
 * priority when both `okapi-postgres` and `okapi-mysql` are on the classpath). Within a single
 * `@AutoConfiguration` pass, `@ConditionalOnBean` cannot reliably observe sibling beans defined
 * in the same auto-config — Spring's `OnBeanCondition` runs at REGISTER_BEAN phase and is
 * evaluated together with the conditions of the bean it is supposed to observe. Splitting
 * Liquibase into a downstream auto-config (`@AutoConfigureAfter(OutboxAutoConfiguration)`)
 * guarantees that by the time Liquibase conditions are evaluated, the chosen `*OutboxStore`
 * bean has already been fully registered and is visible to `@ConditionalOnBean`.
 *
 * **Ordering vs. Spring Boot's own [SpringLiquibase] auto-config:** ordered after Spring Boot's
 * `LiquibaseAutoConfiguration` (3.x and 4.x package paths covered) so the host application's
 * own `liquibase` bean registers first. Spring Boot's `@ConditionalOnMissingBean(SpringLiquibase)`
 * then sees its own bean and stops looking, leaving okapi free to add its uniquely-named
 * `okapiPostgresLiquibase` / `okapiMysqlLiquibase` next to it. Both run on startup with their
 * own changelogs and dedicated tracking tables.
 *
 * Opt out entirely via `okapi.liquibase.enabled=false`.
 */
@AutoConfiguration(
    after = [OutboxAutoConfiguration::class],
    afterName = [
        // Spring Boot 3.x — package path
        "org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration",
        // Spring Boot 4.x — package was reorganized into a separate module
        "org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration",
    ],
)
@EnableConfigurationProperties(OkapiProperties::class)
class OkapiLiquibaseAutoConfiguration {

    /**
     * Auto-configures okapi's PostgreSQL Liquibase migration.
     *
     * **Why a separate class with class-level [ConditionalOnClass]:** placing
     * `@ConditionalOnClass(SpringLiquibase::class)` on the class level (rather than on the
     * `@Bean` method) ensures Spring evaluates the condition via string-name classpath lookup
     * **before** any introspection of the class's methods. Without this, JVM
     * `Class.getDeclaredMethods()` would resolve [SpringLiquibase] in the method return type
     * during configuration parsing — which fails with `NoClassDefFoundError` whenever
     * `liquibase-core` is absent from the consumer's classpath (it is `compileOnly` in
     * okapi-spring-boot, e.g. Flyway-only consumers do not pull it in).
     *
     * **Why class-level [ConditionalOnBean]:** this auto-config is processed AFTER
     * [OutboxAutoConfiguration] (see `@AutoConfigureAfter` on the outer class), so at the
     * time `@ConditionalOnBean(PostgresOutboxStore)` is evaluated, the chosen `*OutboxStore`
     * bean is already registered and visible. When MySQL wins precedence instead, this gate
     * skips the entire class — preventing the dual-Liquibase / wrong-engine-DDL startup
     * failure that would otherwise occur with both modules on the classpath.
     *
     * **Coexistence with the host application's own [SpringLiquibase]:**
     * `@ConditionalOnMissingBean(SpringLiquibase::class)` is intentionally **not** used —
     * okapi's bean is named `okapiPostgresLiquibase` and runs its own bundled changelog with
     * dedicated tracking tables (`okapi_databasechangelog`/`okapi_databasechangeloglock` by
     * default). It coexists alongside Spring Boot's auto-configured `liquibase` bean. Disable
     * okapi's bean via `okapi.liquibase.enabled=false` if the host already includes okapi's
     * changelog from its own master.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(SpringLiquibase::class, PostgresOutboxStore::class)
    @ConditionalOnBean(PostgresOutboxStore::class)
    @ConditionalOnProperty(prefix = "okapi.liquibase", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    class PostgresLiquibaseConfiguration(
        private val dataSources: Map<String, DataSource>,
        private val primaryDataSource: DataSource,
        private val okapiProperties: OkapiProperties,
    ) {
        @Bean("okapiPostgresLiquibase")
        @ConditionalOnMissingBean(name = ["okapiPostgresLiquibase"])
        fun okapiPostgresLiquibase(): SpringLiquibase = SpringLiquibase().apply {
            dataSource = OutboxAutoConfiguration.resolveDataSource(dataSources, primaryDataSource, okapiProperties)
            changeLog = "classpath:com/softwaremill/okapi/db/changelog.xml"
            databaseChangeLogTable = okapiProperties.liquibase.changelogTable
            databaseChangeLogLockTable = okapiProperties.liquibase.changelogLockTable
        }
    }

    /**
     * Auto-configures okapi's MySQL Liquibase migration. See [PostgresLiquibaseConfiguration]
     * for rationale on the conditional placement.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(SpringLiquibase::class, MysqlOutboxStore::class)
    @ConditionalOnBean(MysqlOutboxStore::class)
    @ConditionalOnProperty(prefix = "okapi.liquibase", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    class MysqlLiquibaseConfiguration(
        private val dataSources: Map<String, DataSource>,
        private val primaryDataSource: DataSource,
        private val okapiProperties: OkapiProperties,
    ) {
        @Bean("okapiMysqlLiquibase")
        @ConditionalOnMissingBean(name = ["okapiMysqlLiquibase"])
        fun okapiMysqlLiquibase(): SpringLiquibase = SpringLiquibase().apply {
            dataSource = OutboxAutoConfiguration.resolveDataSource(dataSources, primaryDataSource, okapiProperties)
            changeLog = "classpath:com/softwaremill/okapi/db/mysql/changelog.xml"
            databaseChangeLogTable = okapiProperties.liquibase.changelogTable
            databaseChangeLogLockTable = okapiProperties.liquibase.changelogLockTable
        }
    }

    /**
     * Logs a single startup warning when okapi's Liquibase auto-config is explicitly opted out
     * (`okapi.liquibase.enabled=false`). Without this, a user who flipped the flag months ago
     * has no breadcrumb when they later see "relation okapi_outbox does not exist" at first
     * publish — the link to the opt-out is in the startup log, not the error.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "okapi.liquibase", name = ["enabled"], havingValue = "false")
    class LiquibaseDisabledNotice {
        init {
            LIQUIBASE_DISABLED_LOGGER.warn(
                "okapi.liquibase.enabled=false — okapi will NOT create or migrate the okapi_outbox schema. " +
                    "Ensure your application's migration tool applies " +
                    "classpath:com/softwaremill/okapi/db/changelog.xml " +
                    "(or classpath:com/softwaremill/okapi/db/mysql/changelog.xml for MySQL).",
            )
        }
    }
}
