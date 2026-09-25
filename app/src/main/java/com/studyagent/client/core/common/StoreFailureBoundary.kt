package com.studyagent.client.core.common

import kotlin.coroutines.cancellation.CancellationException

/**
 * Boundary for storage calls whose contract is "report failure as a value" but whose
 * implementation might still throw (IO, corrupt file, a buggy adapter).
 *
 * An unexpected throw becomes [fallback] so callers can fail closed. Coroutine cancellation is
 * NOT a storage failure and is always rethrown: treating it as one would, for example, permanently
 * disable a durable ledger just because the caller that happened to trigger the lazy load was
 * cancelled.
 */
inline fun <T> orOnStoreFailure(fallback: T, block: () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    fallback
}
