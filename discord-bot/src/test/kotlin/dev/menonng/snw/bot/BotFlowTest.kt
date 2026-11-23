package dev.menonng.snw.bot

import com.google.gson.Gson
import com.google.gson.JsonParser
import dev.menonng.snw.logic.discord.Protocol
import dev.menonng.snw.logic.discord.Ulid
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BotFlowTest {
    private val gson = Gson()
    private lateinit var db: Db
    private lateinit var hub: ModHub
    private lateinit var ws: WsServer
    private val alerts = LinkedBlockingQueue<String>()
    private val linked = LinkedBlockingQueue<String>()
    private var port = 0
    private val secret = "test-secret"

    private val front = object : Front {
        override fun alert(text: String) { alerts += text }
        override fun serverStatus(online: Boolean) {}
        override fun linked(discordId: String, nickname: String) { linked += "$discordId:$nickname" }
    }

    @BeforeTest fun up() {
        val f = Files.createTempFile("snwbot", ".db")
        db = Db("jdbc:sqlite:$f")
        hub = ModHub(db, front)
        port = ServerSocket(0).use { it.localPort }
        ws = WsServer("127.0.0.1", port, secret, hub)
        ws.start()
        Thread.sleep(300)
    }

    @AfterTest fun down() { ws.stop(500); db.close() }

    private class Client : WebSocket.Listener {
        val inbox = LinkedBlockingQueue<String>()
        private val buf = StringBuilder()
        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buf.append(data); if (last) { inbox += buf.toString(); buf.setLength(0) }
            webSocket.request(1); return null
        }
        fun next(type: String, seconds: Long = 5): Map<String, Any?> {
            val end = System.currentTimeMillis() + seconds * 1000
            while (System.currentTimeMillis() < end) {
                val m = inbox.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val o = JsonParser.parseString(m).asJsonObject
                if (o["type"].asString == type) @Suppress("UNCHECKED_CAST") return Gson().fromJson(m, Map::class.java) as Map<String, Any?>
            }
            error("$type 이(가) 오지 않음")
        }
    }

    private fun connect(token: String = secret): Pair<WebSocket, Client> {
        val c = Client()
        val w = HttpClient.newHttpClient().newWebSocketBuilder().header(Protocol.HEADER_AUTH, "Bearer $token").header(Protocol.HEADER_VERSION, "1")
            .buildAsync(URI.create("ws://127.0.0.1:$port/snw"), c).get(5, TimeUnit.SECONDS)
        return w to c
    }

    private fun send(w: WebSocket, type: String, data: Map<String, Any?>, id: String = Ulid.next()): String {
        w.sendText(gson.toJson(mapOf("v" to 1, "id" to id, "type" to type, "ts" to System.currentTimeMillis(), "data" to data)), true).get(5, TimeUnit.SECONDS)
        return id
    }

    @Test fun rejectsWrongSecret() {
        assertFailsWith<Exception> { connect("wrong") }
    }

    @Test fun fullLinkFlowAndAlerts() {
        val (w, c) = connect()
        send(w, Protocol.HELLO, mapOf("online" to emptyList<String>()))
        val welcome = c.next(Protocol.WELCOME)
        @Suppress("UNCHECKED_CAST") assertTrue(((welcome["data"] as Map<String, Any?>)["links"] as List<*>).isEmpty())

        // 마크 접속 거부 → 모드가 코드를 보냄
        val uuid = "11111111-2222-3333-4444-555555555555"
        val codeMsg = send(w, Protocol.LINK_CODE, mapOf("uuid" to uuid, "name" to "Steve", "code" to "482913", "expiresAt" to System.currentTimeMillis() + 600_000))
        assertEquals(codeMsg, c.next(Protocol.ACK)["ref"])
        Thread.sleep(200)

        // 디스코드 등록(모장 조회는 가짜) → 코드 입력 → 연동
        val reg = hub.register("d1", "Steve", "영희", true, false, null, null, lookup = { uuid })
        assertTrue(reg is ModHub.RegisterResult.Ok, "$reg")
        assertTrue(hub.redeem("d1", "000000") is ModHub.LinkResult.BadCode)
        val res = hub.redeem("d1", "482913")
        assertTrue(res is ModHub.LinkResult.Linked, "$res")
        assertEquals("d1:영희", linked.poll(2, TimeUnit.SECONDS))
        val confirmed = c.next(Protocol.LINK_CONFIRMED)
        @Suppress("UNCHECKED_CAST") assertEquals(uuid, (confirmed["data"] as Map<String, Any?>)["uuid"])
        assertEquals(1, hub.pendingCount())                       // 모드가 ack 하기 전
        w.sendText(gson.toJson(mapOf("v" to 1, "id" to Ulid.next(), "type" to Protocol.ACK, "ref" to confirmed["id"], "data" to emptyMap<String, Any>())), true).get()
        Thread.sleep(200)
        assertEquals(0, hub.pendingCount())

        // 사건 → #알림 문장(닉네임), 같은 id 두 번은 한 번만
        val killer = "99999999-2222-3333-4444-555555555555"
        hub.register("d2", "Alex", "철수", true, false, null, null, lookup = { killer })
        val id = send(w, Protocol.DEATH, mapOf("victim" to uuid, "killer" to killer, "assists" to emptyList<String>(), "pvp" to true))
        send(w, Protocol.DEATH, mapOf("victim" to uuid, "killer" to killer, "assists" to emptyList<String>(), "pvp" to true), id)
        assertEquals("⚔ 철수 → 영희", alerts.poll(2, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertTrue(alerts.isEmpty(), "중복 알림: $alerts")
        send(w, Protocol.VACCINE, mapOf("person" to uuid, "total" to 3))
        assertEquals("🧪 백신 조각을 누군가 주웠습니다 (서버 전체 3개)", alerts.poll(2, TimeUnit.SECONDS))

        // 다시 연결하면 welcome 에 연동 목록
        w.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(); Thread.sleep(300)
        val (w2, c2) = connect()
        send(w2, Protocol.HELLO, mapOf("online" to listOf(uuid)))
        @Suppress("UNCHECKED_CAST") val links = ((c2.next(Protocol.WELCOME)["data"] as Map<String, Any?>)["links"] as List<Map<String, Any?>>)
        assertEquals(setOf("영희", "철수").filter { n -> links.any { it["nickname"] == n } }.toSet(), setOf("영희"))   // 연동한 사람만
        w2.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get()

        // 일지: 어제·오늘 사건으로 글이 만들어진다(외부 AI 없음)
        val text = DiaryJob(db, ZoneId.of("Asia/Seoul")).write(LocalDate.now(ZoneId.of("Asia/Seoul")))
        assertNotNull(text); assertTrue(text.isNotBlank())
        assertTrue(!text.contains("Steve") && !text.contains("Alex"), "일지에 마크 이름이 나옴")
    }

    @Test fun nicknameRulesApply() {
        val r = hub.register("d3", "Notch", "010-1234", true, false, null, null, lookup = { "aaaaaaaa-2222-3333-4444-555555555555" })
        assertTrue(r is ModHub.RegisterResult.Rejected, "$r")
        assertTrue(hub.register("d4", "NoSuchName", "괜찮은이름", true, false, null, null, lookup = { null }) is ModHub.RegisterResult.UnknownMinecraftName)
    }
}
