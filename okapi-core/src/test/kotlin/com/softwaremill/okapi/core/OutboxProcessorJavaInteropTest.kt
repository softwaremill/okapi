package com.softwaremill.okapi.core

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec

/**
 * Guards the `@JvmOverloads` on [OutboxProcessor]'s constructor. Kotlin default
 * parameters are invisible to Java, so without `@JvmOverloads` a Java caller is
 * forced to pass `listener` and `clock` explicitly. These reflection checks fail
 * if the annotation is dropped — Kotlin call sites would keep compiling and hide
 * the regression otherwise.
 */
class OutboxProcessorJavaInteropTest :
    FunSpec({
        test("exposes a (store, entryProcessor) constructor to Java") {
            shouldNotThrowAny {
                OutboxProcessor::class.java.getConstructor(
                    OutboxStore::class.java,
                    OutboxEntryProcessor::class.java,
                )
            }
        }

        test("exposes a (store, entryProcessor, listener) constructor to Java") {
            shouldNotThrowAny {
                OutboxProcessor::class.java.getConstructor(
                    OutboxStore::class.java,
                    OutboxEntryProcessor::class.java,
                    OutboxProcessorListener::class.java,
                )
            }
        }
    })
