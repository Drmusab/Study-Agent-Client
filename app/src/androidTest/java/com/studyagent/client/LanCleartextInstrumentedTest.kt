package com.studyagent.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The app dials a user-configured PC agent over cleartext `ws://` on the LAN.
 *
 * Regression: the network security config denied cleartext at the base level and listed
 * "ranges" (`192.168.1.0`, …) as `<domain>` entries — but NSC domains are DNS names / exact
 * literals, not CIDR, so every real DHCP address was blocked and only the emulator alias
 * `10.0.2.2` worked. This test speaks cleartext HTTP (which, unlike a raw socket, is subject
 * to the cleartext policy) to this device's own site-local address: it fails with
 * `UnknownServiceException: CLEARTEXT communication ... not permitted` when the policy
 * regresses, and passes when LAN cleartext is allowed.
 */
@RunWith(AndroidJUnit4::class)
class LanCleartextInstrumentedTest {

    @Test(timeout = 30_000)
    fun cleartext_http_to_own_lan_address_is_permitted() {
        val lanIp = siteLocalIpv4()
            ?: throw AssertionError("no site-local IPv4 address; cannot exercise the LAN path")
        val server = ServerSocket(0)
        try {
            val served = CountDownLatch(1)
            Thread {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        readHttpRequest(socket.getInputStream())
                        val body = "PONG".toByteArray(StandardCharsets.UTF_8)
                        val head = ("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\n" +
                            "Connection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8)
                        socket.getOutputStream().write(head)
                        socket.getOutputStream().write(body)
                        socket.getOutputStream().flush()
                    }
                } catch (_: Exception) {
                    // Assertion happens on the response read below.
                } finally {
                    served.countDown()
                }
            }.start()

            val url = URL("http://$lanIp:${server.localPort}/")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.requestMethod = "GET"
                assertEquals(HttpURLConnection.HTTP_OK, connection.responseCode)
                val body = connection.inputStream.readBytes().toString(StandardCharsets.UTF_8)
                assertEquals("PONG", body)
            } finally {
                connection.disconnect()
            }
            assertTrue(served.await(10, TimeUnit.SECONDS))
        } finally {
            server.close()
        }
    }

    private fun siteLocalIpv4(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (nic in interfaces) {
            if (!nic.isUp || nic.isLoopback) continue
            for (address in nic.inetAddresses) {
                if (address is Inet4Address && address.isSiteLocalAddress && !address.isLoopbackAddress) {
                    return address.hostAddress
                }
            }
        }
        return null
    }

    /** Reads request bytes until the blank line that ends the HTTP headers. */
    private fun readHttpRequest(input: java.io.InputStream) {
        val tail = ArrayDeque<Byte>()
        var total = 0
        while (total < 64 * 1024) {
            val byte = input.read()
            if (byte < 0) return
            total++
            tail.addLast(byte.toByte())
            if (tail.size > 4) tail.removeFirst()
            if (tail.size == 4 &&
                tail[0] == '\r'.code.toByte() && tail[1] == '\n'.code.toByte() &&
                tail[2] == '\r'.code.toByte() && tail[3] == '\n'.code.toByte()
            ) return
        }
    }
}
