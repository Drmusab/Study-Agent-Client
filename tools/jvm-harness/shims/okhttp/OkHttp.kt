// Harness stub: compile-only okhttp3 surface (throws at runtime).
package okhttp3
import java.util.concurrent.TimeUnit
class OkHttpClient private constructor() {
    class Builder {
        fun connectTimeout(t: Long, u: TimeUnit) = this
        fun readTimeout(t: Long, u: TimeUnit) = this
        fun writeTimeout(t: Long, u: TimeUnit) = this
        fun pingInterval(t: Long, u: TimeUnit) = this
        fun retryOnConnectionFailure(b: Boolean) = this
        fun build(): OkHttpClient = OkHttpClient()
    }
    fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket = throw UnsupportedOperationException("okhttp stub")
}
class Request private constructor(val url: String) {
    class Builder {
        private var u = ""
        fun url(url: String) = apply { u = url }
        fun header(name: String, value: String) = this
        fun addHeader(name: String, value: String) = this
        fun build() = Request(u)
    }
}
class Response private constructor() { val code: Int = 0 }
interface WebSocket {
    fun send(text: String): Boolean
    fun close(code: Int, reason: String?): Boolean
    fun cancel()
}
abstract class WebSocketListener {
    open fun onOpen(webSocket: WebSocket, response: Response) {}
    open fun onMessage(webSocket: WebSocket, text: String) {}
    open fun onClosing(webSocket: WebSocket, code: Int, reason: String) {}
    open fun onClosed(webSocket: WebSocket, code: Int, reason: String) {}
    open fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {}
}
