package dev.menonng.snw.discord

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.brigadier.Command
import dev.menonng.snw.Snw
import dev.menonng.snw.api.NationApi
import dev.menonng.snw.api.Role
import dev.menonng.snw.logic.discord.Dedup
import dev.menonng.snw.logic.discord.EventMapper
import dev.menonng.snw.logic.discord.LinkCodeBook
import dev.menonng.snw.logic.discord.Protocol
import dev.menonng.snw.logic.discord.Ulid
import dev.menonng.snw.nation.Nations
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.server.MinecraftServer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 디스코드 봇 연동(docs/discord/README.md '연동 모드', protocol.md). 설계의 별도 모드(snw-discord) 대신 snw 안의 꺼 둘 수 있는 부분으로 넣었다.
 * - 봇(127.0.0.1)에 JDK 내장 WebSocket 으로 접속, 비밀 토큰은 환경 변수. 끊기면 1·2·4…60초 간격으로 다시 접속.
 * - 접속 확인: 연동 목록에 없는 UUID 는 로그인 단계에서 거부하고 6자리 코드(10분)를 보여 준다. 봇이 꺼져 있으면 '점검 중'.
 * - 닉네임·소속 반영(link.confirmed, nickname.updated, nation.assign), 공지 방송(announce).
 * - 사건 송신: 킬·영토·관계(snw-api), 이벤트 버스(zomApo 블러드문·둥지·백신 조각·엔딩…). 좌표·채팅·마크 이름은 보내지 않는다.
 * - 잃으면 안 되는 사건(킬·백신 조각·거점·엔딩)은 확인(ack)이 올 때까지 config/snw-discord/outbox.jsonl 에 남긴다.
 * 설정 config/snw-discord.json (기본 꺼짐), 캐시 config/snw-discord/cache.json.
 */
object DiscordLink {
    data class Config(
        var enabled: Boolean = false,
        var botUrl: String = "ws://127.0.0.1:7341/snw",
        var secretEnv: String = "SNW_WS_SECRET",
        var gateEnabled: Boolean = true,
        var gateBypass: MutableList<String> = mutableListOf(),
        var relayChat: Boolean = false,
        var inGameRating: Boolean = false,
        var outboxTypes: MutableList<String> = Protocol.DEFAULT_OUTBOX.toMutableList(),
    )
    data class Cache(val links: MutableMap<String, String> = linkedMapOf(), val nicknames: MutableMap<String, String> = linkedMapOf())

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val compact: Gson = Gson()
    private val dir get() = FabricLoader.getInstance().configDir.resolve("snw-discord")
    private val configFile get() = FabricLoader.getInstance().configDir.resolve("snw-discord.json")
    private val cacheFile get() = dir.resolve("cache.json")
    private val outboxFile get() = dir.resolve("outbox.jsonl")

    var config = Config(); private set
    @Volatile var cache = Cache(); private set
    @Volatile var connected = false; private set
    @Volatile private var ws: WebSocket? = null
    private var server: MinecraftServer? = null
    private val codes = LinkCodeBook()
    private val dedup = Dedup()
    /** 확인을 기다리는 메시지(id → JSON). 순서 유지. */
    private val pending = java.util.Collections.synchronizedMap(LinkedHashMap<String, String>())
    private val outboxIds = ConcurrentHashMap.newKeySet<String>()
    private val sched = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "snw-discord").apply { isDaemon = true } }
    private var attempt = 0
    private var sendChain: CompletableFuture<*> = CompletableFuture.completedFuture(null)
    private val SKY = TextColor.fromRgb(0x39C5BB)
    @Volatile var lastError: String? = null; private set

    // ------------------------------------------------------------------ 설정·캐시

    fun loadConfig() {
        val f = configFile
        config = if (Files.exists(f)) runCatching { gson.fromJson(Files.readString(f), Config::class.java) }.getOrNull() ?: Config()
                 else Config().also { runCatching { Files.createDirectories(f.parent); Files.writeString(f, gson.toJson(it)) } }
        cache = if (Files.exists(cacheFile)) runCatching { gson.fromJson(Files.readString(cacheFile), Cache::class.java) }.getOrNull() ?: Cache() else Cache()
        // 대기열 복원
        if (Files.exists(outboxFile)) runCatching {
            for (line in Files.readAllLines(outboxFile)) if (line.isNotBlank()) {
                val id = JsonParser.parseString(line).asJsonObject["id"].asString
                pending[id] = line; outboxIds += id
            }
        }
    }

    private fun saveCache() = runCatching { Files.createDirectories(dir); Files.writeString(cacheFile, gson.toJson(cache)) }

    private fun rewriteOutbox() = runCatching {
        Files.createDirectories(dir)
        val lines = synchronized(pending) { pending.filterKeys { it in outboxIds }.values.toList() }
        Files.write(outboxFile, lines)
    }

    // ------------------------------------------------------------------ 연결

    private fun secret(): String? = System.getenv(config.secretEnv)?.takeIf { it.isNotBlank() }

    private fun connect() {
        if (!config.enabled || server == null) return
        val token = secret() ?: run { lastError = "환경 변수 ${config.secretEnv} 가 비어 있습니다"; Snw.log.warn("[snw-discord] {}", lastError); return }
        runCatching {
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().newWebSocketBuilder()
                .header(Protocol.HEADER_AUTH, "Bearer $token")
                .header(Protocol.HEADER_VERSION, Protocol.VERSION.toString())
                .buildAsync(URI.create(config.botUrl), Listener)
                .whenComplete { _, err -> if (err != null) { lastError = err.cause?.message ?: err.message; scheduleReconnect() } }
        }.onFailure { lastError = it.message; scheduleReconnect() }
    }

    private fun scheduleReconnect() {
        connected = false; ws = null
        if (!config.enabled || server == null) return
        val wait = Protocol.backoffSeconds(attempt++)
        sched.schedule({ connect() }, wait, TimeUnit.SECONDS)
    }

    private object Listener : WebSocket.Listener {
        private val buf = StringBuilder()
        override fun onOpen(webSocket: WebSocket) {
            ws = webSocket; connected = true; attempt = 0; lastError = null
            webSocket.request(1)
            Snw.log.info("[snw-discord] 봇에 연결했습니다")
            server?.execute { hello() }
        }
        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buf.append(data)
            if (last) { val text = buf.toString(); buf.setLength(0); server?.execute { receive(text) } }
            webSocket.request(1)
            return null
        }
        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            lastError = "닫힘 $statusCode $reason"; scheduleReconnect(); return null
        }
        override fun onError(webSocket: WebSocket, error: Throwable) { lastError = error.message; scheduleReconnect() }
    }

    private fun rawSend(json: String) {
        val w = ws ?: return
        synchronized(this) { sendChain = sendChain.handle { _, _ -> null }.thenCompose { w.sendText(json, true) } }
    }

    /** 봇으로 보낸다. 확인이 필요한 종류는 확인이 올 때까지 기억하고, 대기열 종류는 디스크에도 남긴다. */
    fun send(type: String, data: Map<String, Any?>) {
        if (!config.enabled) return
        val id = Ulid.next()
        val env = linkedMapOf("v" to Protocol.VERSION, "id" to id, "type" to type, "ts" to System.currentTimeMillis(), "data" to data)
        val json = compact.toJson(env)
        if (Protocol.needsAck(type)) {
            pending[id] = json
            if (type in config.outboxTypes) { outboxIds += id; runCatching { Files.createDirectories(dir); Files.writeString(outboxFile, json + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND) } }
        }
        if (connected) rawSend(json)
    }

    private fun hello() {
        val s = server ?: return
        val mods = listOf("snw", "zomapo", "courierpost").filter { FabricLoader.getInstance().isModLoaded(it) }
            .associateWith { FabricLoader.getInstance().getModContainer(it).get().metadata.version.friendlyString }
        send(Protocol.HELLO, mapOf("serverVersion" to s.serverVersion, "mods" to mods, "online" to s.playerList.players.map { it.uuid.toString() }))
        // 확인이 안 온 메시지를 다시 보낸다(봇이 id 로 중복을 거른다)
        synchronized(pending) { pending.values.toList() }.forEach { rawSend(it) }
    }

    // ------------------------------------------------------------------ 받기

    private fun ack(id: String) = rawSend(compact.toJson(mapOf("v" to Protocol.VERSION, "id" to Ulid.next(), "type" to Protocol.ACK, "ts" to System.currentTimeMillis(), "ref" to id, "data" to emptyMap<String, Any>())))

    private fun api(): NationApi? = NationApi.getOrNull()

    internal fun receive(text: String) {
        val o = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
        val type = o["type"]?.asString ?: return
        if (type == Protocol.ACK) {
            val ref = o["ref"]?.asString ?: return
            pending.remove(ref)
            if (outboxIds.remove(ref)) rewriteOutbox()
            return
        }
        val id = o["id"]?.asString ?: return
        if (!dedup.firstTime(id)) { if (Protocol.needsAck(type)) ack(id); return }
        val d = o["data"]?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        when (type) {
            Protocol.WELCOME -> {
                val links = linkedMapOf<String, String>(); val nicks = linkedMapOf<String, String>()
                d.getAsJsonArray("links")?.forEach { e -> val l = e.asJsonObject
                    val u = l["uuid"].asString; links[u] = l["discordId"].asString
                    l["nickname"]?.takeIf { !it.isJsonNull }?.asString?.let { nicks[u] = it } }
                cache = Cache(links, nicks); saveCache()
                for ((u, n) in nicks) runCatching { api()?.setNickname(UUID.fromString(u), n) }
                d.getAsJsonArray("nations")?.forEach { e -> val a = e.asJsonObject
                    val u = UUID.fromString(a["uuid"].asString)
                    if (api()?.nationOf(u) == null) assign(u, a["nation"]?.asString, a["role"]?.asString) }
            }
            Protocol.LINK_CONFIRMED -> {
                val u = d["uuid"].asString
                cache.links[u] = d["discordId"].asString
                d["nickname"]?.takeIf { !it.isJsonNull }?.asString?.let { n -> cache.nicknames[u] = n; runCatching { api()?.setNickname(UUID.fromString(u), n) } }
                saveCache()
            }
            Protocol.LINK_REVOKED -> {
                val u = d["uuid"].asString
                cache.links.remove(u); saveCache()
                server?.playerList?.getPlayer(UUID.fromString(u))?.connection?.disconnect(Component.literal("디스코드 연동이 해제되었습니다."))
            }
            Protocol.NICKNAME_UPDATED -> {
                val u = d["uuid"].asString; val n = d["nickname"].asString
                cache.nicknames[u] = n; saveCache()
                runCatching { api()?.setNickname(UUID.fromString(u), n) }
            }
            Protocol.NATION_ASSIGN -> d.getAsJsonArray("assignments")?.forEach { e -> val a = e.asJsonObject
                assign(UUID.fromString(a["uuid"].asString), a["nation"]?.takeIf { !it.isJsonNull }?.asString, a["role"]?.asString) }
            Protocol.ANNOUNCE -> {
                val s = server ?: return
                val text = d["text"]?.asString ?: return
                val title = d["title"]?.takeIf { !it.isJsonNull }?.asString
                for (p in s.playerList.players) {
                    p.sendSystemMessage(Component.literal("[공지] $text").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFF7E00))))
                    if (title != null) { p.connection.send(ClientboundSetTitleTextPacket(Component.literal(title))); p.connection.send(ClientboundSetSubtitleTextPacket(Component.literal(text))) }
                }
            }
            Protocol.STATS_REQUEST -> sendStats()
            Protocol.RATING_REQUEST -> {
                RatingGui.onRequest(d)
                server?.playerList?.players?.forEach { p -> if (RatingGui.available(p)) notifyRating(p) }
            }
            else -> Snw.log.debug("[snw-discord] 알 수 없는 메시지 {}", type)
        }
        if (Protocol.needsAck(type)) ack(id)
    }

    private fun assign(u: UUID, nation: String?, role: String?) {
        val r = runCatching { Role.valueOf((role ?: "CITIZEN").uppercase()) }.getOrDefault(Role.CITIZEN)
        runCatching { api()?.assign(u, nation?.let { dev.menonng.snw.api.NationId(it) }, r) }.onFailure { Snw.log.warn("[snw-discord] 국가 배정 실패 {}: {}", u, it.message) }
    }

    private fun sendStats() {
        val s = server ?: return
        val ids = (Nations.registry.alive().flatMap { it.members } + s.playerList.players.map { it.uuid }).distinct()
        send(Protocol.STATS, mapOf("players" to ids.map { id ->
            val k = dev.menonng.snw.protect.Protection.stats(id)
            mapOf("uuid" to id.toString(), "k" to k.kills, "d" to k.deaths, "a" to k.assists, "dPvp" to k.deathsPvp, "zombieScore" to Nations.zombieScore(id))
        }))
    }

    // ------------------------------------------------------------------ 접속 확인

    /** 로그인 허가(믹스인 PlayerListLoginMixin): 거부할 이유가 있으면 화면에 보일 문구. 네트워크 스레드에서 불릴 수 있다. */
    @JvmStatic
    fun loginDenied(uuid: UUID, name: String, op: Boolean): Component? {
        if (!config.enabled || !config.gateEnabled || op) return null
        val u = uuid.toString()
        if (u in cache.links || u in config.gateBypass || name in config.gateBypass) return null
        if (!connected) return Component.literal("디스코드 봇 점검 중입니다. 잠시 후 다시 시도하세요.")
        val c = codes.issue(u, name, System.currentTimeMillis())
        send(Protocol.LINK_CODE, mapOf("uuid" to u, "name" to name, "code" to c.code, "expiresAt" to c.expiresAt))
        return Component.literal("디스코드 #등록 에서 [연동 코드 입력]을 누르고 코드 ")
            .append(Component.literal(c.code).withStyle(Style.EMPTY.withColor(SKY).withBold(true)))
            .append(Component.literal(" 을(를) 입력하세요.\n코드는 10분 동안 쓸 수 있습니다."))
    }

    // ------------------------------------------------------------------ 사건 연결

    private fun hook() {
        val api = NationApi.getOrNull() ?: return
        api.events().onPlayerKill { r ->
            send(Protocol.DEATH, mapOf("victim" to r.victim.toString(), "killer" to r.killer?.toString(), "assists" to r.assists.map { it.toString() },
                "pvp" to r.pvp, "victimNation" to api.nationOf(r.victim)?.id?.value, "killerNation" to r.killer?.let { api.nationOf(it)?.id?.value }))
        }
        api.events().onClaimChanged { n, added, removed ->
            send(Protocol.CLAIM, mapOf("nation" to n.value, "added" to added.size, "removed" to removed.size, "total" to api.territory(n).size))
        }
        api.events().onRelationChanged { a, b, from, to -> send(Protocol.RELATION, mapOf("a" to a.value, "b" to b.value, "from" to from.name, "to" to to.name)) }
        api.eventBus().subscribe(null) { e ->
            val out = EventMapper.map(e.type, e.data) ?: return@subscribe
            if (out.type == Protocol.VACCINE) server?.execute { send(out.type, out.data + ("nation" to (out.data["person"] as? String)?.let { runCatching { api.nationOf(UUID.fromString(it))?.id?.value }.getOrNull() }) + ("total" to dev.menonng.snw.project.VaccineBoard.total())) }
            else send(out.type, out.data)
        }
    }

    private fun notifyRating(p: net.minecraft.server.level.ServerPlayer) =
        p.sendSystemMessage(Component.literal("상호평가가 열렸습니다. 디스코드 DM 이나 게임 안 ").append(Component.literal("/평가").withStyle(Style.EMPTY.withColor(SKY)))
            .append(Component.literal(" 상자창에서 평가할 수 있습니다.")))

    fun status(): String = if (!config.enabled) "디스코드 연동이 꺼져 있습니다(config/snw-discord.json 의 enabled)." else
        "봇 ${if (connected) "연결됨" else "연결 안 됨"}${lastError?.let { " ($it)" } ?: ""} · 연동 ${cache.links.size}명 · 확인 대기 ${pending.size}건(대기열 ${outboxIds.size})"

    fun register() {
        loadConfig()
        ServerLifecycleEvents.SERVER_STARTED.register { s -> server = s; hook(); connect() }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ -> server = null; runCatching { ws?.sendClose(WebSocket.NORMAL_CLOSURE, "server stopping") } }
        ServerPlayConnectionEvents.JOIN.register { h, _, _ ->
            send(Protocol.JOIN, mapOf("uuid" to h.player.uuid.toString(), "ts" to System.currentTimeMillis()))
            if (RatingGui.available(h.player)) notifyRating(h.player)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { h, _ -> send(Protocol.LEAVE, mapOf("uuid" to h.player.uuid.toString(), "ts" to System.currentTimeMillis())) }
        sched.scheduleAtFixedRate({ runCatching { if (connected) ws?.sendPing(ByteBuffer.allocate(0)) } }, Protocol.PING_SECONDS, Protocol.PING_SECONDS, TimeUnit.SECONDS)
        CommandRegistrationCallback.EVENT.register { d, _, _ ->
            d.register(Commands.literal("평가").executes { c -> c.source.player?.let { RatingGui.open(it) }; Command.SINGLE_SUCCESS })
            d.register(Commands.literal("snwd").requires { Commands.LEVEL_GAMEMASTERS.check(it.permissions()) }
                .then(Commands.literal("status").executes { c -> c.source.sendSuccess({ Component.literal(status()) }, false); Command.SINGLE_SUCCESS })
                .then(Commands.literal("reload").executes { c ->
                    val was = config.enabled
                    loadConfig()
                    if (config.enabled && !was) connect() else if (!config.enabled) runCatching { ws?.sendClose(WebSocket.NORMAL_CLOSURE, "disabled") }
                    c.source.sendSuccess({ Component.literal("설정을 다시 읽었습니다. ${status()}") }, true); Command.SINGLE_SUCCESS
                })
                .then(Commands.literal("rating-preview").executes { c -> c.source.player?.let { RatingGui.preview(it) }; Command.SINGLE_SUCCESS })
                .then(Commands.literal("resync").executes { c -> if (connected) hello(); c.source.sendSuccess({ Component.literal(if (connected) "봇에 다시 동기화를 요청했습니다." else "봇에 연결되어 있지 않습니다.") }, true); Command.SINGLE_SUCCESS }))
        }
    }
}
