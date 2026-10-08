// Harness stub — androidx.compose.foundation.interaction. See Runtime.kt for the policy.
package androidx.compose.foundation.interaction

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

interface Interaction

class MutableInteractionSource : AutoCloseable {
    val interactions: MutableState<List<Interaction>> = mutableStateOf(emptyList())
    suspend fun emit(interaction: Interaction) = Unit
    fun tryEmit(interaction: Interaction): Boolean = true
    override fun close() = Unit
}
