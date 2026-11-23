package dev.menonng.snw.bot

import dev.menonng.snw.logic.discord.Protocol
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshakeBuilder
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.security.MessageDigest

/**
 * 모드가 붙는 WebSocket 서버(protocol.md 1절). 127.0.0.1 에만 열고, 경로 /snw, `Authorization: Bearer <비밀>`·`X-SNW-Protocol: 1` 을 확인한다.
 * 연결은 하나만 둔다(새로 붙으면 이전 연결을 닫는다).
 */
class WsServer(host: String, port: Int, private val secret: String, private val hub: ModHub) : WebSocketServer(InetSocketAddress(host, port)) {
    @Volatile private var current: WebSocket? = null

    init { isReuseAddr = true; connectionLostTimeout = 60 }

    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    override fun onWebsocketHandshakeReceivedAsServer(conn: WebSocket, draft: Draft, request: ClientHandshake): ServerHandshakeBuilder {
        val b = super.onWebsocketHandshakeReceivedAsServer(conn, draft, request)
        if (request.resourceDescriptor?.substringBefore('?') != Protocol.PATH) throw InvalidDataException(CloseFrame.POLICY_VALIDATION, "경로가 다릅니다")
        val auth = request.getFieldValue(Protocol.HEADER_AUTH) ?: ""
        if (!same(auth, "Bearer $secret")) throw InvalidDataException(CloseFrame.POLICY_VALIDATION, "인증 실패")
        return b
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        if (!Protocol.compatible(handshake.getFieldValue(Protocol.HEADER_VERSION))) { conn.close(Protocol.CLOSE_POLICY, "프로토콜 주 버전이 다릅니다"); return }
        current?.takeIf { it !== conn }?.close(CloseFrame.NORMAL, "새 연결")
        current = conn
        hub.onConnect { text -> if (conn.isOpen) conn.send(text) }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) { if (conn === current) { current = null; hub.onDisconnect() } }
    override fun onMessage(conn: WebSocket, message: String) { if (conn === current) runCatching { hub.onText(message) }.onFailure { System.err.println("[snw-bot] 메시지 처리 오류: $it") } }
    override fun onError(conn: WebSocket?, ex: Exception) { System.err.println("[snw-bot] WebSocket 오류: ${ex.message}") }
    override fun onStart() { println("[snw-bot] 모드 연결을 기다립니다: ${address}") }
}
