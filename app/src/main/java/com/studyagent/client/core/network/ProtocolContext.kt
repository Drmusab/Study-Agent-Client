package com.studyagent.client.core.network

/**
 * Negotiated protocol context - single source of truth for message construction.
 * Avoids defaulting every message to protocol 1 after v2 negotiation.
 * Uses BuildConfig.VERSION_NAME via injected provider, never hard-coded.
 */
data class ProtocolContext(
    val negotiatedVersion: String = "1",
    val sessionId: String? = null,
    val clientName: String = "StudyAgent-Android",
    val clientVersion: String = ClientInfoProvider.getClientVersion(),
    val platform: String = "android",
    val androidApi: Int = ClientInfoProvider.getAndroidApi(),
    val supportedVersions: List<String> = listOf("1", "2"),
    val versionProvider: (() -> String)? = null
) {
    val isV2: Boolean
        get() = negotiatedVersion == "2"

    val effectiveClientVersion: String
        get() = versionProvider?.invoke() ?: clientVersion

    fun withSession(sessionId: String?): ProtocolContext =
        copy(sessionId = sessionId ?: this.sessionId)

    fun withNegotiatedVersion(version: String): ProtocolContext =
        copy(negotiatedVersion = version)

    fun createHello(
        supportedProtocols: List<String> = supportedVersions,
        authToken: String? = null
    ): com.studyagent.client.core.models.ClientMessage.Hello {
        return com.studyagent.client.core.models.ClientMessage.Hello(
            protocolVersion = negotiatedVersion,
            clientVersion = effectiveClientVersion,
            supportedVersions = supportedProtocols,
            clientName = clientName,
            platform = platform,
            androidApi = androidApi
        )
    }

    fun createStartSession(deck: String?, messageId: String): com.studyagent.client.core.models.ClientMessage.StartSession {
        return com.studyagent.client.core.models.ClientMessage.StartSession(
            protocolVersion = negotiatedVersion,
            messageId = messageId,
            sessionId = sessionId,
            deck = deck
        )
    }
}

object ClientInfoProvider {
    fun getClientVersion(): String {
        return try {
            // Use BuildConfig if available, never hard-code
            val buildConfig = Class.forName("com.studyagent.client.BuildConfig")
            val field = buildConfig.getField("VERSION_NAME")
            field.get(null) as? String ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    fun getAndroidApi(): Int {
        return try {
            android.os.Build.VERSION.SDK_INT
        } catch (_: Exception) {
            34
        }
    }

    fun createContext(sessionId: String? = null, negotiatedVersion: String = "1"): ProtocolContext {
        return ProtocolContext(
            negotiatedVersion = negotiatedVersion,
            sessionId = sessionId,
            clientVersion = getClientVersion(),
            androidApi = getAndroidApi()
        )
    }
}


