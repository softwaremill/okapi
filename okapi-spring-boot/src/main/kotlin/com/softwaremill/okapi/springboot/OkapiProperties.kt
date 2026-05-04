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
     * Liquibase tracking-table names used by okapi's bundled migrations.
     *
     * Defaults to dedicated tables (`okapi_databasechangelog` / `okapi_databasechangeloglock`)
     * so okapi's migration history is isolated from the host application's. Override via
     * `okapi.liquibase.changelog-table` / `okapi.liquibase.changelog-lock-table` to point at
     * existing tables (e.g. `databasechangelog`) when migrating from a setup that shared them.
     */
    data class Liquibase(
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
