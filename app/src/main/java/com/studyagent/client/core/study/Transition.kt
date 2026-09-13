package com.studyagent.client.core.study

/**
 * Result of a pure reducer invocation (§7).
 */
data class Transition(
    val newState: SessionMachineState,
    val effects: List<StudyEffect> = emptyList(),
    val accepted: Boolean = true,
    val rejectionReason: String? = null
) {
    companion object {
        fun stay(state: SessionMachineState, reason: String, effect: StudyEffect? = null): Transition =
            Transition(
                newState = state.recordRejected(
                    // synthetic rejected event will be replaced by caller
                    object : StudyEvent {
                        override val debugName: String get() = "Rejected"
                    },
                    reason
                ),
                effects = listOfNotNull(effect),
                accepted = false,
                rejectionReason = reason
            )

        fun reject(state: SessionMachineState, event: StudyEvent, reason: String): Transition {
            val withLog = state.recordRejected(event, reason)
            return Transition(
                newState = withLog,
                effects = listOf(StudyEffect.LogRejected(event.debugName, reason, state.phase, state.currentCardId)),
                accepted = false,
                rejectionReason = reason
            )
        }
    }
}
