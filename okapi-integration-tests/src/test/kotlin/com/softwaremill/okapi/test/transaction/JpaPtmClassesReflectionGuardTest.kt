package com.softwaremill.okapi.test.transaction

import com.softwaremill.okapi.springboot.OutboxAutoConfiguration
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty

/**
 * Guards `OutboxAutoConfiguration.JPA_HIBERNATE_PTM_CLASSES` against full bit-rot: if all listed
 * FQCNs become unresolvable (Spring rename / package move), `extractDataSource`'s JPA branch
 * silently never fires and every JPA user falls through to the WARN-only path. Runs in
 * okapi-integration-tests because spring-orm is a `testImplementation` there. Mirrors the
 * `@AutoConfigureAfter`-resolves guard on OkapiMicrometerAutoConfiguration.
 */
class JpaPtmClassesReflectionGuardTest : FunSpec({

    test("at least one FQCN in JPA_HIBERNATE_PTM_CLASSES resolves on the runtime classpath") {
        val classLoader = OutboxAutoConfiguration::class.java.classLoader
        val resolvable = OutboxAutoConfiguration.JPA_HIBERNATE_PTM_CLASSES.filter { fqcn ->
            try {
                Class.forName(fqcn, false, classLoader)
                true
            } catch (_: ClassNotFoundException) {
                false
            }
        }
        withClue(
            "None of ${OutboxAutoConfiguration.JPA_HIBERNATE_PTM_CLASSES} resolves — the JPA branch " +
                "of extractDataSource is now dead code (Spring likely renamed a class or moved a " +
                "package). Update the set.",
        ) {
            resolvable.shouldNotBeEmpty()
        }
    }
})
