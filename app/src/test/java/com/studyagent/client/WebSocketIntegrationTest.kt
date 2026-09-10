package com.studyagent.client

import com.studyagent.client.core.common.DefaultDispatcherProvider
import com.studyagent.client.core.models.ServerMessage
import com.studyagent.client.core.models.ServerProfile
import com.studyagent.client.core.network.WebSocketAgentConnection
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebSocketIntegrationTest {

    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @After
    fun tearDown() {
        try {
            mockWebServer.shutdown()
        } catch (_: Exception) {}
    }

    @Test
    fun testRealWebSocketConnectionAndMessageExchange() {
        runBlocking {
            val serverReceivedHello = CountDownLatch(1)
            var serverSocket: WebSocket? = null

            mockWebServer.enqueue(
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                        serverSocket = webSocket
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.contains("\"type\":\"hello\"")) {
                            serverReceivedHello.countDown()
                            webSocket.send("""{"type":"pong"}""")
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }
                })
            )

            val connection = WebSocketAgentConnection(DefaultDispatcherProvider())
            val profile = ServerProfile(
                name = "Test Server",
                host = mockWebServer.hostName,
                port = mockWebServer.port,
                path = "/"
            )

            withTimeout(5000L) {
                connection.connect(profile)
                val pong = connection.incomingMessages.filterIsInstance<ServerMessage.Pong>().first()
                assertTrue(pong is ServerMessage.Pong)
                assertTrue(serverReceivedHello.await(3, TimeUnit.SECONDS))
                connection.disconnect("Test complete")
                serverSocket?.close(1000, "Clean shutdown")
            }
        }
    }
}
