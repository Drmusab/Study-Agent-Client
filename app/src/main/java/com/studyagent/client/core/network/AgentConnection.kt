package com.studyagent.client.core.network

import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.ServerMessage
import com.studyagent.client.core.models.ServerProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface AgentConnection {
    suspend fun connect(profile: ServerProfile)
    suspend fun disconnect(reason: String = "User requested disconnect")
    suspend fun send(message: ClientMessage): Boolean

    val connectionState: StateFlow<ConnectionState>
    val incomingMessages: Flow<ServerMessage>
    val currentProfile: ServerProfile?
}
