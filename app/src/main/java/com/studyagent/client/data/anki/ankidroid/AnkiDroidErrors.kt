package com.studyagent.client.data.anki.ankidroid

/**
 * GATE 02 — typed AnkiDroid integration failures.
 *
 * The rule this file exists to enforce: **a provider failure never reaches the UI as a
 * `Throwable`, a provider message or a bare `false`.** Every failure is classified once, here,
 * into a category that answers the three questions of §37 ("what happened / can the user fix
 * it / what should they do") plus the evidence that justified the classification.
 *
 * ## What is deliberately *not* stored
 *
 * - No `Throwable`: upper layers must not be able to re-throw, print or pattern-match on
 *   provider exceptions (GATE 02 §36).
 * - No raw provider message. Provider text can contain absolute collection paths
 *   (`/storage/emulated/0/AnkiDroid/collection.anki2`), which §69 forbids us to log or
 *   display. Only [AnkiDroidFailure.evidenceToken] (a constant from this file) and the
 *   exception *simple class name* survive.
 *
 * ## Why messages are inspected at all
 *
 * ContentProvider exceptions cross a Binder boundary. The platform marshals the well-known
 * exception types (SecurityException, IllegalStateException, IllegalArgumentException,
 * UnsupportedOperationException, NullPointerException, FileNotFoundException, …) by
 * *type*, and everything else as a generic `RuntimeException` whose message begins with the
 * original `toString()` — i.e. the AnkiDroid-side class name and message are preserved inside
 * the message string. Classification therefore uses (a) the exception type first and (b) the
 * documented AnkiDroid-side signature where the type alone cannot decide. A signature that
 * does not match is never guessed: the failure degrades to [AnkiDroidFailureCategory.PROVIDER_ERROR]
 * or [AnkiDroidFailureCategory.UNEXPECTED] and stays visible in diagnostics (§61).
 */
enum class AnkiDroidFailureCategory {

    /** The provider refused the call because the caller lacks the AnkiDroid permission. */
    PERMISSION_DENIED,

    /** AnkiDroid reports that its storage/collection setup is not complete yet. */
    COLLECTION_NOT_READY,

    /** A collection exists but could not be used (storage/back-end error). */
    COLLECTION_UNAVAILABLE,

    /** The collection is (temporarily) locked by another AnkiDroid process/screen. */
    COLLECTION_LOCKED,

    /** The bounded probe budget expired before the provider answered. */
    TIMEOUT,

    /** The installed AnkiDroid publishes a provider spec this build does not support. */
    UNSUPPORTED_API,

    /** The provider rejected the *shape* of the probe: the pinned contract may be stale. */
    CONTRACT_MISMATCH,

    /** The provider answered with an internal error that is not attributable further. */
    PROVIDER_ERROR,

    /**
     * Discovery-level conclusion: no usable endpoint exists (nothing resolved, the expected
     * package has no provider, a foreign package serves the authority, or the provider is
     * disabled). Distinct from [PROVIDER_ERROR], which means an endpoint did answer and failed.
     */
    ENDPOINT_UNAVAILABLE,

    /** Nothing above matched. Recorded with its exception class so it stays observable. */
    UNEXPECTED
}

/**
 * Which observation justified [AnkiDroidFailure.category].
 *
 * Diagnostics may show this; users never see it. It is the difference between "we know
 * because the provider said so by type" and "we matched a documented signature" — an
 * audit trail for exactly the mapping §61 warns about.
 */
enum class AnkiDroidFailureEvidence {
    /** The platform reported the endpoint permission as not granted. */
    PLATFORM_PERMISSION_CHECK,

    /** No endpoint authority resolved. */
    PROVIDER_NOT_RESOLVED,

    /** The expected package is installed, but its provider authority did not resolve. */
    PACKAGE_PRESENT_PROVIDER_MISSING,

    /** An authority resolved, but a package other than the expected one is serving it. */
    UNEXPECTED_PROVIDER_PACKAGE,

    /** The provider resolved with `enabled = false`. */
    PROVIDER_DISABLED,

    /** The provider's published spec is below the minimum this build supports. */
    SPEC_BELOW_MINIMUM,

    /** A `SecurityException` (the provider's own documented refusal, `throwSecurityException`). */
    SECURITY_EXCEPTION_TYPE,

    /** A wrapped message carrying the provider's documented permission signature. */
    SECURITY_SIGNATURE,

    /** A message matching the documented "storage not configured" signature. */
    COLLECTION_SETUP_SIGNATURE,

    /** A collection/storage exception class surfaced by AnkiDroid. */
    STORAGE_EXCEPTION,

    /** A lock/contention signature (the provider or its database is busy). */
    LOCK_SIGNATURE,

    /** The remaining operation budget expired. */
    TIMEOUT_BUDGET,

    /** The provider rejected the probe's own URI/projection shape. */
    PROBE_CONTRACT_REJECTED,

    /** An `IllegalStateException` with no documented signature: type known, cause not. */
    UNCLASSIFIED_PROVIDER_STATE,

    /** Nothing matched. */
    UNCLASSIFIED
}

/** Where the failure happened. Keeps the classifier honest about what it can conclude. */
enum class AnkiDroidOperationStage {
    PROVIDER_RESOLUTION,
    PERMISSION_CHECK,
    COLLECTION_PROBE
}

/**
 * One classified AnkiDroid failure. Immutable, content-free, safe to log.
 */
data class AnkiDroidFailure(
    val category: AnkiDroidFailureCategory,
    val evidence: AnkiDroidFailureEvidence,
    /** Simple class name of the original exception, when there was one. Never a stack trace. */
    val exceptionClass: String? = null,
    /** Stable token from [AnkiDroidContractSignatures], or a bounded literal. Never raw text. */
    val evidenceToken: String? = null
) {
    /** Short technical label for diagnostics: `category/evidence/token`. */
    val technicalLabel: String
        get() = buildString {
            append(category.name)
            append('/')
            append(evidence.name)
            evidenceToken?.let { append('/').append(it) }
        }
}

/**
 * Documented AnkiDroid-side signatures used as *evidence* when the exception type alone is
 * ambiguous. Every entry is traceable to AnkiDroid source or its published API docs; the list is
 * pinned to the release this gate verified (v2.24.1) and is intentionally small.
 *
 * Adding an entry requires evidence — a signature invented from imagination is how a
 * "CollectionNotReady" gets reported for unrelated breakage (§61).
 */
internal object AnkiDroidContractSignatures {

    /** `CardContentProvider.throwSecurityException`: "Permission not granted for: …". */
    const val PERMISSION_NOT_GRANTED: String = "Permission not granted"

    /**
     * The permission signature documented for the *install-order* case: the API javadoc states
     * `SecurityException` is thrown when the permission was not granted "e.g. due to install
     * order bug" — i.e. the caller requested the permission before AnkiDroid existed.
     */
    const val INSTALL_ORDER: String = "install order"

    /**
     * AnkiDroid's own description of the unconfigured-storage state (`FlashCardsContract` KDoc:
     * "If AnkiDroid's storage is not yet configured (the user has not completed first-run
     * setup), operations on this provider throw IllegalStateException").
     */
    val COLLECTION_SETUP: List<String> = listOf(
        "storage is not yet configured",
        "not yet configured",
        "not initialized",
        "not been initialized",
        "has not been set up",
        "no collection"
    )

    /** Storage failures raised by AnkiDroid's collection helper. */
    val STORAGE_CLASSES: List<String> = listOf(
        "SystemStorageException",
        "StorageAccessException",
        "SQLiteCantOpenDatabaseException",
        "SQLiteException",
        "FileNotFoundException",
        "UnknownDatabaseVersionException"
    )

    /** Contention/lock signatures (AnkiDroid's backend reports a locked database). */
    val LOCK: List<String> = listOf(
        "database is locked",
        "SQLiteDatabaseLockedException",
        "BackendDbLockedException",
        "CollectionNotOpen",
        "locked"
    )

    /**
     * The provider rejecting the probe's own request shape (its `query()` throws
     * `IllegalArgumentException("uri … is not supported")` / `"Unknown column …"`), which means
     * our pinned contract and the installed AnkiDroid disagree.
     */
    val CONTRACT_REJECTED: List<String> = listOf(
        "is not supported",
        "Unknown column",
        "Unknown URI"
    )

    /** Binder-level transport failures. */
    val TRANSPORT: List<String> = listOf(
        "DeadObjectException",
        "RemoteException",
        "TransactionTooLargeException",
        "Broken pipe"
    )
}

/**
 * Pure, deterministic failure classifier (JVM-testable; no Android types).
 *
 * Mapping table (category → domain state) is applied by [DefaultAnkiDroidDetector] and
 * documented in `docs/ANKIDROID_INTEGRATION.md` §5.
 */
object AnkiDroidFailureClassifier {

    /** The probe budget expired. Never inferred from an exception type. */
    fun timedOut(stage: AnkiDroidOperationStage): AnkiDroidFailure = AnkiDroidFailure(
        category = AnkiDroidFailureCategory.TIMEOUT,
        evidence = AnkiDroidFailureEvidence.TIMEOUT_BUDGET,
        exceptionClass = null,
        evidenceToken = "probe_budget_${stage.name.lowercase()}"
    )

    /**
     * Classify [throwable] observed at [stage].
     *
     * Order matters and is deliberate: permission first (it is the provider's own typed
     * refusal and must never be swallowed, §20), then documented setup/lock/storage
     * signatures, then contract rejection, then the unclassified-but-observed fallbacks.
     */
    fun classify(throwable: Throwable, stage: AnkiDroidOperationStage): AnkiDroidFailure {
        val exceptionClass = throwable::class.java.simpleName
        val message = throwable.message ?: ""

        if (throwable is SecurityException) {
            return AnkiDroidFailure(
                category = AnkiDroidFailureCategory.PERMISSION_DENIED,
                evidence = AnkiDroidFailureEvidence.SECURITY_EXCEPTION_TYPE,
                exceptionClass = exceptionClass,
                evidenceToken = AnkiDroidContractSignatures.PERMISSION_NOT_GRANTED
            )
        }

        if (message.contains(AnkiDroidContractSignatures.PERMISSION_NOT_GRANTED, ignoreCase = true)) {
            return AnkiDroidFailure(
                category = AnkiDroidFailureCategory.PERMISSION_DENIED,
                evidence = AnkiDroidFailureEvidence.SECURITY_SIGNATURE,
                exceptionClass = exceptionClass,
                evidenceToken = AnkiDroidContractSignatures.PERMISSION_NOT_GRANTED
            )
        }

        val setupSignature = AnkiDroidContractSignatures.COLLECTION_SETUP.firstOrNull {
            message.contains(it, ignoreCase = true)
        }
        if (setupSignature != null) {
            return AnkiDroidFailure(
                category = AnkiDroidFailureCategory.COLLECTION_NOT_READY,
                evidence = AnkiDroidFailureEvidence.COLLECTION_SETUP_SIGNATURE,
                exceptionClass = exceptionClass,
                evidenceToken = setupSignature
            )
        }

        val lockSignature = AnkiDroidContractSignatures.LOCK.firstOrNull {
            message.contains(it, ignoreCase = true)
        }
        if (lockSignature != null) {
            return AnkiDroidFailure(
                category = AnkiDroidFailureCategory.COLLECTION_LOCKED,
                evidence = AnkiDroidFailureEvidence.LOCK_SIGNATURE,
                exceptionClass = exceptionClass,
                evidenceToken = lockSignature
            )
        }

        val storageClass = AnkiDroidContractSignatures.STORAGE_CLASSES.firstOrNull {
            exceptionClass == it || message.contains(it, ignoreCase = true)
        }
        if (storageClass != null) {
            return AnkiDroidFailure(
                category = AnkiDroidFailureCategory.COLLECTION_UNAVAILABLE,
                evidence = AnkiDroidFailureEvidence.STORAGE_EXCEPTION,
                exceptionClass = exceptionClass,
                evidenceToken = storageClass
            )
        }

        val transportSignature = AnkiDroidContractSignatures.TRANSPORT.firstOrNull {
            exceptionClass == it || message.contains(it, ignoreCase = true)
        }
        if (transportSignature != null) {
            return AnkiDroidFailure(
                category = AnkiDroidFailureCategory.PROVIDER_ERROR,
                evidence = AnkiDroidFailureEvidence.UNCLASSIFIED_PROVIDER_STATE,
                exceptionClass = exceptionClass,
                evidenceToken = transportSignature
            )
        }

        val contractSignature = AnkiDroidContractSignatures.CONTRACT_REJECTED.firstOrNull {
            message.contains(it, ignoreCase = true)
        }
        if (throwable is IllegalArgumentException || throwable is UnsupportedOperationException ||
            contractSignature != null
        ) {
            return AnkiDroidFailure(
                category = AnkiDroidFailureCategory.CONTRACT_MISMATCH,
                evidence = AnkiDroidFailureEvidence.PROBE_CONTRACT_REJECTED,
                exceptionClass = exceptionClass,
                evidenceToken = contractSignature ?: exceptionClass
            )
        }

        if (throwable is IllegalStateException) {
            // Type recognised, cause NOT documented for this stage: reported as a provider error
            // so a wrong cause is never presented as "AnkiDroid setup incomplete" (§61).
            return AnkiDroidFailure(
                category = AnkiDroidFailureCategory.PROVIDER_ERROR,
                evidence = AnkiDroidFailureEvidence.UNCLASSIFIED_PROVIDER_STATE,
                exceptionClass = exceptionClass,
                evidenceToken = null
            )
        }

        return AnkiDroidFailure(
            category = AnkiDroidFailureCategory.UNEXPECTED,
            evidence = AnkiDroidFailureEvidence.UNCLASSIFIED,
            exceptionClass = exceptionClass,
            evidenceToken = null
        )
    }
}
