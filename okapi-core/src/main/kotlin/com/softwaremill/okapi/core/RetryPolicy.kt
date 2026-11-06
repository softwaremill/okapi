package com.softwaremill.okapi.core

import java.time.Duration
import java.util.concurrent.ThreadLocalRandom
import java.util.random.RandomGenerator

/**
 * Decides whether a retriable failure is retried and when (issue #131).
 *
 * [maxRetries] counts retries after the initial delivery attempt; once they are used up the entry is
 * marked [OutboxStatus.FAILED]. Retry `n` (1-based) is due [initialBackoff] × [backoffMultiplier]^(n-1)
 * after the failed attempt, capped at [maxBackoff] and shortened by up to [jitter] (a fraction in
 * `[0, 1]`) at random, so entries that failed together do not all retry in the same tick. With the
 * defaults, five retries span roughly five minutes of downstream outage before an entry fails.
 */
data class RetryPolicy @JvmOverloads constructor(
    val maxRetries: Int,
    val initialBackoff: Duration = DEFAULT_INITIAL_BACKOFF,
    val backoffMultiplier: Double = DEFAULT_BACKOFF_MULTIPLIER,
    val maxBackoff: Duration = DEFAULT_MAX_BACKOFF,
    val jitter: Double = DEFAULT_JITTER,
) {
    init {
        require(maxRetries >= 0) { "maxRetries must be >= 0, got: $maxRetries" }
        require(!initialBackoff.isNegative) { "initialBackoff must be >= 0, got: $initialBackoff" }
        require(backoffMultiplier >= 1.0) { "backoffMultiplier must be >= 1, got: $backoffMultiplier" }
        require(maxBackoff >= initialBackoff) { "maxBackoff ($maxBackoff) must be >= initialBackoff ($initialBackoff)" }
        require(jitter in 0.0..1.0) { "jitter must be between 0 and 1, got: $jitter" }
    }

    /** Returns `true` if [currentRetries] has not yet reached [maxRetries]. */
    fun shouldRetry(currentRetries: Int): Boolean = currentRetries < maxRetries

    /** How long to wait before retry number [retry] (1 for the first retry), jitter applied. */
    @JvmOverloads
    fun backoff(retry: Int, random: RandomGenerator = ThreadLocalRandom.current()): Duration {
        require(retry >= 1) { "retry must be >= 1, got: $retry" }
        val maxMillis = maxBackoff.toMillis().toDouble()
        val exponential = initialBackoff.toMillis() * Math.pow(backoffMultiplier, (retry - 1).toDouble())
        val capped = minOf(exponential, maxMillis)
        val jittered = capped * (1.0 - jitter * random.nextDouble())
        return Duration.ofMillis(jittered.toLong())
    }

    companion object {
        val DEFAULT_INITIAL_BACKOFF: Duration = Duration.ofSeconds(10)
        const val DEFAULT_BACKOFF_MULTIPLIER: Double = 2.0
        val DEFAULT_MAX_BACKOFF: Duration = Duration.ofMinutes(10)
        const val DEFAULT_JITTER: Double = 0.2
    }
}
