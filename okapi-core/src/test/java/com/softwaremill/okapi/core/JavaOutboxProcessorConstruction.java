package com.softwaremill.okapi.core;

/**
 * Java-side guard for {@code @JvmOverloads} on {@link OutboxProcessor}'s constructor.
 *
 * <p>These call sites compile only if {@code OutboxProcessor} exposes the shorter,
 * default-omitting constructors to Java. Drop {@code @JvmOverloads} and okapi-core's test
 * compilation fails right here — which is the point: a Kotlin-only test keeps compiling
 * (Kotlin fills defaults at the call site) and would hide the regression. {@code
 * OutboxProcessorJavaInteropTest} calls these so the guard also runs.
 */
public final class JavaOutboxProcessorConstruction {

    private JavaOutboxProcessorConstruction() {}

    /** {@code OutboxProcessor(store, entryProcessor)} — listener and clock default. */
    public static OutboxProcessor withStoreAndProcessor(OutboxStore store, OutboxEntryProcessor entryProcessor) {
        return new OutboxProcessor(store, entryProcessor);
    }

    /** {@code OutboxProcessor(store, entryProcessor, listener)} — clock defaults. */
    public static OutboxProcessor withListener(
            OutboxStore store, OutboxEntryProcessor entryProcessor, OutboxProcessorListener listener) {
        return new OutboxProcessor(store, entryProcessor, listener);
    }
}
