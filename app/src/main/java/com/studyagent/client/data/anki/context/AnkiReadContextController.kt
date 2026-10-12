package com.studyagent.client.data.anki.context

import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiBackendContext
import com.studyagent.client.core.anki.AnkiBackendDescriptor
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiBackendResolver2
import com.studyagent.client.core.anki.AnkiCollectionIdentity
import com.studyagent.client.core.anki.AnkiContinuationBlockReason
import com.studyagent.client.core.anki.AnkiContinuationVerdict
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiIdentityAssurancePolicy
import com.studyagent.client.core.anki.AnkiIdentityObservation
import com.studyagent.client.core.anki.AnkiIdentityContinuation
import com.studyagent.client.core.anki.AnkiOperationClass
import com.studyagent.client.core.anki.AnkiReadContext
import com.studyagent.client.core.anki.AnkiReadContextGeneration
import com.studyagent.client.core.anki.AnkiRequiredCapability
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.BackendPreference
import com.studyagent.client.core.anki.BackendResolutionFailure
import com.studyagent.client.core.anki.BackendResolutionRequest2
import com.studyagent.client.core.anki.BackendResolutionResult2
import com.studyagent.client.core.anki.unavailabilityError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/** Why an unbound/exact-context read could not be bound. All values are content-free. */
enum class AnkiReadContextFailureKind {
    RESOLUTION_FAILED,
    BACKEND_NOT_REGISTERED,
    BACKEND_UNAVAILABLE,
    CAPABILITY_UNSUPPORTED,
    AMBIGUOUS_BACKEND,
    INVALID_CONFIGURATION,
    COLLECTION_IDENTITY_MISMATCH,
    COLLECTION_IDENTITY_UNVERIFIABLE,
    COLLECTION_IDENTITY_UNAVAILABLE,
    PREFERENCE_CHANGED_DURING_BINDING,
    BACKEND_CHECK_FAILED,
    INVALID_REFERENCE
}

/** Typed, safe failure projected by existing read screens. */
data class AnkiReadContextFailure(
    val kind: AnkiReadContextFailureKind,
    val error: AnkiError,
    val backendId: AnkiBackendId? = null,
    val resolutionFailure: BackendResolutionFailure? = null,
    val considered: Set<AnkiBackendId> = emptySet()
)

sealed interface AnkiReadContextBindingResult {
    data class Bound(val binding: AnkiReadContextBinding) : AnkiReadContextBindingResult
    data class Failed(val failure: AnkiReadContextFailure) : AnkiReadContextBindingResult
}

/**
 * The sole 21F binding boundary. Resolver use is limited to a new unbound read; resource-originated
 * and restored routes bind by their exact BackendId and never consult the current preference.
 */
class AnkiReadContextController(
    private val registry: AnkiBackendRegistry,
    /** The existing GATE 21E source of truth, passed as a snapshot source—not copied here. */
    private val preference: StateFlow<BackendPreference>,
    private val maxPreBindingAttempts: Int = DEFAULT_BINDING_ATTEMPTS
) {
    private val activeLeases = ConcurrentHashMap<AnkiReadContextGeneration, AnkiReadContextLease>()
    private val readAccess = AnkiReadContextAccess()

    init { require(maxPreBindingAttempts > 0) }

    /**
     * Resolve and bind one genuinely new context. The preference is sampled for the attempt, the
     * exact backend is refreshed, then the resolver is run again against fresh descriptors before
     * the context is published. A preference/availability race before binding retries only within
     * this unbound boundary. Once returned, this context never resolves again.
     */
    suspend fun bindNewContext(
        requiredCapabilities: Set<AnkiRequiredCapability>,
        purpose: String
    ): AnkiReadContextBindingResult {
        require(purpose.isNotBlank())
        repeat(maxPreBindingAttempts) {
            val capturedPreference = preference.value
            val request = BackendResolutionRequest2(
                preference = capturedPreference,
                requiredCapabilities = requiredCapabilities,
                purpose = purpose
            )
            val initial = AnkiBackendResolver2.resolve(request, registry.descriptors())
            val initiallyResolved = initial as? BackendResolutionResult2.Resolved
                ?: return resolutionFailure(initial)
            val candidate = registry.find(initiallyResolved.backendId)
                ?: return failed(
                    kind = AnkiReadContextFailureKind.BACKEND_NOT_REGISTERED,
                    error = AnkiError.InvalidRequest("read_context_backend_not_registered"),
                    backendId = initiallyResolved.backendId
                )

            try {
                candidate.refreshAvailability()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                // If refresh made this candidate unavailable, a *new* AUTO context may re-resolve;
                // it may not do so once a binding exists.
                if (preference.value != capturedPreference || candidate.availability.value !is com.studyagent.client.core.anki.AnkiAvailability.Ready) {
                    return@repeat
                }
                return failed(
                    kind = AnkiReadContextFailureKind.BACKEND_CHECK_FAILED,
                    error = AnkiError.Unknown(cause = failure::class.java.simpleName),
                    backendId = candidate.id
                )
            }

            if (registry.find(candidate.id) !== candidate) return@repeat
            if (preference.value != capturedPreference) return@repeat

            val afterRefresh = AnkiBackendResolver2.resolve(request, registry.descriptors())
            val refreshedResolution = afterRefresh as? BackendResolutionResult2.Resolved
                ?: return resolutionFailure(afterRefresh)
            if (refreshedResolution.backendId != candidate.id) return@repeat
            if (candidate.currentCollectionIdentity?.backendId?.let { it != candidate.id } == true) {
                return failed(
                    kind = AnkiReadContextFailureKind.COLLECTION_IDENTITY_MISMATCH,
                    error = AnkiError.InvalidRequest("read_context_collection_identity_mismatch"),
                    backendId = candidate.id
                )
            }

            // No suspension between this final preference/resolver sample and publishing the new
            // generation. A changed preference is a new attempt, never an active-context redirect.
            if (preference.value != capturedPreference) return@repeat
            val finalResolution = AnkiBackendResolver2.resolve(request, registry.descriptors())
            val finalResolved = finalResolution as? BackendResolutionResult2.Resolved
                ?: return resolutionFailure(finalResolution)
            if (finalResolved.backendId != candidate.id || preference.value != capturedPreference) return@repeat

            val identity = candidate.currentCollectionIdentity
            if (identity != null && identity.backendId != candidate.id) {
                return failed(
                    kind = AnkiReadContextFailureKind.COLLECTION_IDENTITY_MISMATCH,
                    error = AnkiError.InvalidRequest("read_context_collection_identity_mismatch"),
                    backendId = candidate.id
                )
            }
            val collection = identity?.takeIf { it.collectionKey != null }
                ?.let { AnkiCollectionIdentity(candidate.id, it.collectionKey) }
            val context = AnkiReadContext.create(AnkiBackendContext(candidate.id, collection))
            val binding = createBinding(context, candidate, isLiveBindingInThisProcess = true)
            if (registry.find(candidate.id) !== candidate ||
                preference.value != capturedPreference ||
                candidate.currentCollectionIdentity != identity
            ) {
                release(binding)
                return@repeat
            }
            return AnkiReadContextBindingResult.Bound(binding)
        }

        return failed(
            kind = AnkiReadContextFailureKind.PREFERENCE_CHANGED_DURING_BINDING,
            error = AnkiError.InvalidRequest("read_context_resolution_obsolete")
        )
    }

    /**
     * Bind an existing resource/route to its exact origin. This is OP-R2: no resolver, no AUTO and
     * no sibling fallback. `collectionKey == null` remains UNKNOWN, not a wildcard or a match.
     */
    suspend fun bindReferenceContext(
        backendId: AnkiBackendId,
        collectionKey: String?,
        purpose: String,
        requiredCapabilities: Set<AnkiRequiredCapability> = emptySet()
    ): AnkiReadContextBindingResult = bindExactContext(
        backendId = backendId,
        collectionKey = collectionKey,
        purpose = purpose,
        requiredCapabilities = requiredCapabilities,
        isLiveBindingInThisProcess = false
    )

    /** Process/ViewModel restoration preserves the exact saved identity but allocates a fresh gen. */
    suspend fun restoreContext(
        backendId: AnkiBackendId,
        collectionKey: String?,
        purpose: String,
        requiredCapabilities: Set<AnkiRequiredCapability> = emptySet()
    ): AnkiReadContextBindingResult = bindExactContext(
        backendId = backendId,
        collectionKey = collectionKey,
        purpose = purpose,
        requiredCapabilities = requiredCapabilities,
        isLiveBindingInThisProcess = false
    )

    /**
     * Continue navigation within an existing read context when its in-process owner is still alive.
     * The backend + collection tuple in [resource] is checked against [sourceContext]; the matching
     * context generation is retained. If navigation was restored after process death, the active
     * lease is absent and the exact reference is rebound with a fresh generation (never re-resolved).
     */
    suspend fun continueContext(
        sourceContext: AnkiReadContext,
        resourceBackendId: AnkiBackendId,
        resourceCollectionKey: String?,
        purpose: String,
        requiredCapabilities: Set<AnkiRequiredCapability> = emptySet()
    ): AnkiReadContextBindingResult {
        require(purpose.isNotBlank())
        if (sourceContext.backendId != resourceBackendId) {
            return failed(
                kind = AnkiReadContextFailureKind.INVALID_REFERENCE,
                error = AnkiError.InvalidRequest(AnkiReadContextAccess.READ_CONTEXT_BACKEND_MISMATCH),
                backendId = resourceBackendId
            )
        }
        val resourceVerdict = AnkiIdentityContinuation.continuationVerdict(
            AnkiOperationClass.OP_R2,
            AnkiIdentityAssurancePolicy.check(
                sourceContext.backendContext.observation,
                AnkiIdentityObservation(resourceBackendId, resourceCollectionKey),
                boundIsLiveInThisProcess = false
            )
        )
        if (resourceVerdict is AnkiContinuationVerdict.Blocked) {
            return failed(
                kind = if (resourceVerdict.reason == AnkiContinuationBlockReason.COLLECTION_MISMATCH) {
                    AnkiReadContextFailureKind.COLLECTION_IDENTITY_MISMATCH
                } else {
                    AnkiReadContextFailureKind.COLLECTION_IDENTITY_UNVERIFIABLE
                },
                error = AnkiError.InvalidRequest(resourceVerdict.reason.toReadContextToken()),
                backendId = resourceBackendId
            )
        }

        val lease = activeLeases[sourceContext.generation]
            ?.takeIf { it.isValid && it.context == sourceContext }
        if (lease == null) {
            val restored = bindExactContext(
                backendId = sourceContext.backendId,
                collectionKey = sourceContext.collectionKey,
                purpose = purpose,
                requiredCapabilities = requiredCapabilities,
                isLiveBindingInThisProcess = false
            )
            return validateResourceOnBinding(restored, resourceBackendId, resourceCollectionKey)
        }

        val binding = try {
            bindingForExistingLease(lease)
        } catch (_: IllegalStateException) {
            val restored = bindExactContext(
                backendId = sourceContext.backendId,
                collectionKey = sourceContext.collectionKey,
                purpose = purpose,
                requiredCapabilities = requiredCapabilities,
                isLiveBindingInThisProcess = false
            )
            return validateResourceOnBinding(restored, resourceBackendId, resourceCollectionKey)
        }
        try {
            binding.backend.refreshAvailability()
        } catch (cancellation: CancellationException) {
            binding.invalidateLease()
            throw cancellation
        } catch (failure: Throwable) {
            binding.invalidateLease()
            return failed(
                kind = AnkiReadContextFailureKind.BACKEND_CHECK_FAILED,
                error = AnkiError.Unknown(cause = failure::class.java.simpleName),
                backendId = resourceBackendId
            )
        }
        readAccess.validateBinding(binding)?.let { error ->
            if (AnkiReadContextAccess.isContextSafetyFailure(error)) binding.revokeContext()
            else binding.invalidateLease()
            return failedFromError(error, resourceBackendId)
        }
        capabilityFailure(binding.backend, requiredCapabilities)?.let { failure ->
            binding.invalidateLease()
            return failure
        }
        return validateResourceOnBinding(
            AnkiReadContextBindingResult.Bound(binding),
            resourceBackendId,
            resourceCollectionKey
        )
    }

    /** Revoke a context before publishing a replacement after an integrity failure. */
    fun invalidate(binding: AnkiReadContextBinding) {
        binding.revokeContext()
    }

    /** Release only this navigation owner; a sibling route may still retain the same generation. */
    fun release(binding: AnkiReadContextBinding) {
        binding.invalidateLease()
    }

    private fun createBinding(
        context: AnkiReadContext,
        backend: AnkiBackend,
        isLiveBindingInThisProcess: Boolean
    ): AnkiReadContextBinding {
        activeLeases.entries.removeIf { !it.value.isValid }
        val lease = AnkiReadContextLease(
            context = context,
            backend = backend,
            isLiveBindingInThisProcess = isLiveBindingInThisProcess,
            isStillRegistered = { registry.find(backend.id) === backend }
        )
        check(activeLeases.putIfAbsent(context.generation, lease) == null) {
            "A read-context generation must be unique"
        }
        return bindingForExistingLease(lease)
    }

    private fun bindingForExistingLease(lease: AnkiReadContextLease): AnkiReadContextBinding =
        AnkiReadContextBinding(
            context = lease.context,
            backend = lease.backend,
            isLiveBindingInThisProcess = lease.isLiveBindingInThisProcess,
            lease = lease,
            onLastOwnerReleased = { released ->
                activeLeases.remove(released.context.generation, released)
            }
        )

    private fun validateResourceOnBinding(
        result: AnkiReadContextBindingResult,
        resourceBackendId: AnkiBackendId,
        resourceCollectionKey: String?
    ): AnkiReadContextBindingResult = when (result) {
        is AnkiReadContextBindingResult.Failed -> result
        is AnkiReadContextBindingResult.Bound -> {
            val binding = result.binding
            val error = readAccess.validateResource(binding, resourceBackendId, resourceCollectionKey)
            if (error == null) result else {
                binding.invalidateLease()
                failedFromError(error, resourceBackendId)
            }
        }
    }

    private fun failedFromError(
        error: AnkiError,
        backendId: AnkiBackendId
    ): AnkiReadContextBindingResult.Failed {
        val detail = (error as? AnkiError.InvalidRequest)?.detail
        val kind = when (detail) {
            AnkiReadContextAccess.READ_CONTEXT_COLLECTION_IDENTITY_MISMATCH ->
                AnkiReadContextFailureKind.COLLECTION_IDENTITY_MISMATCH
            AnkiReadContextAccess.READ_CONTEXT_COLLECTION_IDENTITY_UNVERIFIABLE ->
                AnkiReadContextFailureKind.COLLECTION_IDENTITY_UNVERIFIABLE
            AnkiReadContextAccess.READ_CONTEXT_BACKEND_MISMATCH ->
                AnkiReadContextFailureKind.INVALID_REFERENCE
            else -> if (error is AnkiError.BackendUnavailable ||
                error is AnkiError.PermissionRequired || error is AnkiError.CollectionUnavailable ||
                error is AnkiError.ProviderUnavailable
            ) AnkiReadContextFailureKind.BACKEND_UNAVAILABLE else AnkiReadContextFailureKind.INVALID_REFERENCE
        }
        return failed(kind, error, backendId)
    }

    private suspend fun bindExactContext(
        backendId: AnkiBackendId,
        collectionKey: String?,
        purpose: String,
        requiredCapabilities: Set<AnkiRequiredCapability>,
        isLiveBindingInThisProcess: Boolean
    ): AnkiReadContextBindingResult {
        require(purpose.isNotBlank())
        val backend = registry.find(backendId)
            ?: return failed(
                kind = AnkiReadContextFailureKind.BACKEND_NOT_REGISTERED,
                error = AnkiError.InvalidRequest("read_context_backend_not_registered"),
                backendId = backendId
            )

        try {
            backend.refreshAvailability()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return failed(
                kind = AnkiReadContextFailureKind.BACKEND_CHECK_FAILED,
                error = AnkiError.Unknown(cause = failure::class.java.simpleName),
                backendId = backendId
            )
        }
        if (registry.find(backendId) !== backend) {
            return failed(
                kind = AnkiReadContextFailureKind.BACKEND_NOT_REGISTERED,
                error = AnkiError.InvalidRequest("read_context_backend_not_registered"),
                backendId = backendId
            )
        }
        backend.availability.value.unavailabilityError()?.let { error ->
            return failed(
                kind = AnkiReadContextFailureKind.BACKEND_UNAVAILABLE,
                error = error,
                backendId = backendId
            )
        }
        capabilityFailure(backend, requiredCapabilities)?.let { return it }

        val identity = backend.currentCollectionIdentity
        if (identity != null && identity.backendId != backendId) {
            return failed(
                kind = AnkiReadContextFailureKind.COLLECTION_IDENTITY_MISMATCH,
                error = AnkiError.InvalidRequest("read_context_collection_identity_mismatch"),
                backendId = backendId
            )
        }
        val check = AnkiIdentityAssurancePolicy.check(
            bound = AnkiIdentityObservation(backendId, collectionKey),
            live = AnkiIdentityObservation(backendId, identity?.collectionKey),
            boundIsLiveInThisProcess = false
        )
        when (val verdict = AnkiIdentityContinuation.continuationVerdict(AnkiOperationClass.OP_R2, check)) {
            is AnkiContinuationVerdict.Permitted -> Unit
            is AnkiContinuationVerdict.Blocked -> return failed(
                kind = when (verdict.reason) {
                    AnkiContinuationBlockReason.COLLECTION_MISMATCH ->
                        AnkiReadContextFailureKind.COLLECTION_IDENTITY_MISMATCH
                    AnkiContinuationBlockReason.IDENTITY_UNVERIFIABLE_DEGRADED ->
                        AnkiReadContextFailureKind.COLLECTION_IDENTITY_UNVERIFIABLE
                    else -> AnkiReadContextFailureKind.INVALID_REFERENCE
                },
                error = AnkiError.InvalidRequest(verdict.reason.toReadContextToken()),
                backendId = backendId
            )
        }

        val boundCollection = collectionKey?.let { AnkiCollectionIdentity(backendId, it) }
        val context = AnkiReadContext.create(AnkiBackendContext(backendId, boundCollection))
        return AnkiReadContextBindingResult.Bound(
            createBinding(context, backend, isLiveBindingInThisProcess)
        )
    }

    private fun resolutionFailure(result: BackendResolutionResult2): AnkiReadContextBindingResult.Failed {
        val failure = result as? BackendResolutionResult2.Failed ?: return failed(
            kind = AnkiReadContextFailureKind.RESOLUTION_FAILED,
            error = AnkiError.InvalidRequest("read_context_resolution_failed")
        )
        val kind = when (failure.failure) {
            BackendResolutionFailure.EXPLICIT_BACKEND_MISSING -> AnkiReadContextFailureKind.BACKEND_NOT_REGISTERED
            BackendResolutionFailure.EXPLICIT_BACKEND_UNAVAILABLE,
            BackendResolutionFailure.NO_ELIGIBLE_BACKEND,
            BackendResolutionFailure.PERMISSION_REQUIRED,
            BackendResolutionFailure.AUTHENTICATION_REQUIRED -> AnkiReadContextFailureKind.BACKEND_UNAVAILABLE
            BackendResolutionFailure.CAPABILITY_UNSUPPORTED -> AnkiReadContextFailureKind.CAPABILITY_UNSUPPORTED
            BackendResolutionFailure.AMBIGUOUS_CONFIGURATION -> AnkiReadContextFailureKind.AMBIGUOUS_BACKEND
            BackendResolutionFailure.CONFIGURATION_INVALID -> AnkiReadContextFailureKind.INVALID_CONFIGURATION
            BackendResolutionFailure.NO_BACKEND_CONFIGURED -> AnkiReadContextFailureKind.BACKEND_NOT_REGISTERED
        }
        return AnkiReadContextBindingResult.Failed(
            AnkiReadContextFailure(
                kind = kind,
                error = failure.error,
                resolutionFailure = failure.failure,
                considered = failure.considered
            )
        )
    }

    private fun capabilityFailure(
        backend: AnkiBackend,
        requiredCapabilities: Set<AnkiRequiredCapability>
    ): AnkiReadContextBindingResult.Failed? {
        if (requiredCapabilities.isEmpty()) return null
        val missing = AnkiBackendDescriptor.of(backend).missingCapabilities(requiredCapabilities)
        if (missing.isEmpty()) return null
        return AnkiReadContextBindingResult.Failed(
            AnkiReadContextFailure(
                kind = AnkiReadContextFailureKind.CAPABILITY_UNSUPPORTED,
                error = AnkiError.UnsupportedAction("required_capability"),
                backendId = backend.id,
                resolutionFailure = BackendResolutionFailure.CAPABILITY_UNSUPPORTED,
                considered = setOf(backend.id)
            )
        )
    }

    private fun failed(
        kind: AnkiReadContextFailureKind,
        error: AnkiError,
        backendId: AnkiBackendId? = null
    ) = AnkiReadContextBindingResult.Failed(
        AnkiReadContextFailure(kind = kind, error = error, backendId = backendId)
    )

    companion object {
        const val DEFAULT_BINDING_ATTEMPTS = 3
    }
}

/**
 * Executes a protected read against exactly the backend and collection bound to [binding].
 * Identity is checked immediately before and after each backend call; stale generation/lease checks
 * are still required at the ViewModel state-update boundary (cancellation is never sufficient).
 */
class AnkiReadContextAccess {

    suspend fun <T> read(
        binding: AnkiReadContextBinding,
        requiredCapabilities: Set<AnkiRequiredCapability> = emptySet(),
        operation: suspend (AnkiBackend) -> AnkiResult<T>,
        validateResult: (T) -> AnkiError? = { null }
    ): AnkiResult<T> {
        validateBinding(binding, requiredCapabilities)?.let { return AnkiResult.Failure(it) }
        val result = try {
            operation(binding.backend)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            AnkiResult.Failure(AnkiError.Unknown(cause = failure::class.java.simpleName))
        }

        // The postflight runs even for a backend error: an obsolete error must not mask the current
        // context's identity failure, and the caller still applies its request-generation guard.
        validateBinding(binding, requiredCapabilities)?.let { return AnkiResult.Failure(it) }
        return when (result) {
            is AnkiResult.Failure -> result
            is AnkiResult.Success -> {
                val validationError = validateResult(result.value)
                if (validationError != null && isContextSafetyFailure(validationError)) {
                    binding.revokeContext()
                }
                validationError?.let { AnkiResult.Failure(it) } ?: result
            }
        }
    }

    /** Validate one resource identity against a bound context (IDs are never compared globally). */
    fun validateResource(
        binding: AnkiReadContextBinding,
        backendId: AnkiBackendId,
        collectionKey: String?
    ): AnkiError? = identityError(
        binding = binding,
        observed = AnkiIdentityObservation(backendId, collectionKey),
        // A deck/card/model reference is not the live configured backend binding; resource
        // identity therefore never receives SessionContinuityVerified.
        inProcess = false
    )

    /** Read-boundary check, also used after restoring a ViewModel/navigation owner. */
    fun validateBinding(
        binding: AnkiReadContextBinding,
        requiredCapabilities: Set<AnkiRequiredCapability> = emptySet()
    ): AnkiError? {
        if (!binding.isValid) return AnkiError.InvalidRequest(READ_CONTEXT_INVALIDATED)
        if (binding.backend.id != binding.context.backendId) {
            return AnkiError.InvalidRequest(READ_CONTEXT_BACKEND_MISMATCH)
        }
        val current = binding.backend.currentCollectionIdentity
        if (current != null && current.backendId != binding.context.backendId) {
            val mismatch = AnkiError.InvalidRequest(READ_CONTEXT_COLLECTION_IDENTITY_MISMATCH)
            binding.revokeContext()
            return mismatch
        }
        val identityError = identityError(
            binding = binding,
            observed = AnkiIdentityObservation(binding.backend.id, current?.collectionKey),
            inProcess = binding.isLiveBindingInThisProcess
        )
        if (identityError != null) {
            binding.revokeContext()
            return identityError
        }
        // Identity integrity takes precedence: an offline response must not disguise a changed or
        // unverifiable collection and keep stale rows/cursors alive under the old context.
        binding.backend.availability.value.unavailabilityError()?.let { return it }
        capabilityError(binding.backend, requiredCapabilities)?.let { return it }
        return null
    }

    private fun capabilityError(
        backend: AnkiBackend,
        requiredCapabilities: Set<AnkiRequiredCapability>
    ): AnkiError? = if (requiredCapabilities.isNotEmpty() &&
        AnkiBackendDescriptor.of(backend).missingCapabilities(requiredCapabilities).isNotEmpty()
    ) AnkiError.UnsupportedAction("required_capability") else null

    private fun identityError(
        binding: AnkiReadContextBinding,
        observed: AnkiIdentityObservation,
        inProcess: Boolean
    ): AnkiError? {
        val check = AnkiIdentityAssurancePolicy.check(
            bound = binding.context.backendContext.observation,
            live = observed,
            boundIsLiveInThisProcess = inProcess
        )
        return when (val verdict = AnkiIdentityContinuation.continuationVerdict(AnkiOperationClass.OP_R2, check)) {
            is AnkiContinuationVerdict.Permitted -> null
            is AnkiContinuationVerdict.Blocked -> AnkiError.InvalidRequest(verdict.reason.toReadContextToken())
        }
    }

    companion object {
        const val READ_CONTEXT_INVALIDATED = "read_context_invalidated"
        const val READ_CONTEXT_BACKEND_MISMATCH = "read_context_backend_mismatch"
        const val READ_CONTEXT_COLLECTION_IDENTITY_MISMATCH = "read_context_collection_identity_mismatch"
        const val READ_CONTEXT_COLLECTION_IDENTITY_UNVERIFIABLE = "read_context_collection_identity_unverifiable"

        fun isContextSafetyFailure(error: AnkiError): Boolean =
            (error as? AnkiError.InvalidRequest)?.detail?.startsWith("read_context_") == true
    }
}

private fun AnkiContinuationBlockReason.toReadContextToken(): String = when (this) {
    AnkiContinuationBlockReason.COLLECTION_MISMATCH -> AnkiReadContextAccess.READ_CONTEXT_COLLECTION_IDENTITY_MISMATCH
    AnkiContinuationBlockReason.IDENTITY_UNVERIFIABLE_DEGRADED -> AnkiReadContextAccess.READ_CONTEXT_COLLECTION_IDENTITY_UNVERIFIABLE
    AnkiContinuationBlockReason.FOREIGN_BACKEND -> AnkiReadContextAccess.READ_CONTEXT_BACKEND_MISMATCH
    AnkiContinuationBlockReason.NEW_BINDING_BLOCKED_BY_APPEARED_KEY -> AnkiReadContextAccess.READ_CONTEXT_COLLECTION_IDENTITY_UNVERIFIABLE
    else -> "read_context_continuation_blocked"
}
