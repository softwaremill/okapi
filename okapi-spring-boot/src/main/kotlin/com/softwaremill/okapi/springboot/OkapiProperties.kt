package com.softwaremill.okapi.springboot

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@ConfigurationProperties(prefix = "okapi")
@Validated
data class OkapiProperties(
    val datasourceQualifier: String? = null,
    val liquibase: Liquibase = Liquibase(),
) {
    init {
        require(datasourceQualifier == null || datasourceQualifier.isNotBlank()) {
            "okapi.datasource-qualifier must not be blank. Set it to the bean name of the outbox DataSource, or remove the property."
        }
    }

    /**
     * Liquibase auto-configuration settings for okapi's bundled migrations.
     *
     * - [enabled]: when `false`, okapi's `SpringLiquibase` bean is not registered; the application
     *   is responsible for applying okapi's changelog (e.g. via its own `<include file="..."/>`).
     *   Default: `true`. Disable when okapi's bean would shadow the application's own
     *   `SpringLiquibase` (Spring Boot's `LiquibaseAutoConfiguration` uses
     *   `@ConditionalOnMissingBean(SpringLiquibase::class)` by type).
     * - [changelogTable] / [changelogLockTable]: tracking-table names. Defaults to dedicated
     *   tables (`okapi_databasechangelog` / `okapi_databasechangeloglock`) so okapi's migration
     *   history is isolated from the host application's. Override to point at existing tables
     *   (e.g. `databasechangelog`) when migrating from a setup that shared them.
     */
    data class Liquibase(
        val enabled: Boolean = true,
        val changelogTable: String = "okapi_databasechangelog",
        val changelogLockTable: String = "okapi_databasechangeloglock",
    ) {
        init {
            require(changelogTable.isNotBlank()) {
                "okapi.liquibase.changelog-table must not be blank."
            }
            require(changelogLockTable.isNotBlank()) {
                "okapi.liquibase.changelog-lock-table must not be blank."
            }
        }
    }
}
