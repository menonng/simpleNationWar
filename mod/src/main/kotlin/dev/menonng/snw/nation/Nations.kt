package dev.menonng.snw.nation

import com.google.gson.GsonBuilder
import dev.menonng.snw.Snw
import dev.menonng.snw.api.ChunkKey
import dev.menonng.snw.api.CoreView
import dev.menonng.snw.api.KillRecord
import dev.menonng.snw.api.NationApi
import dev.menonng.snw.api.NationEvents
import dev.menonng.snw.api.NationId
import dev.menonng.snw.api.NationView
import dev.menonng.snw.api.Relation
import dev.menonng.snw.api.Role
import dev.menonng.snw.api.ServerEvent
import dev.menonng.snw.api.ServerEventBus
import dev.menonng.snw.logic.state.NationRegistry
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.TeamColor
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 국가 상태를 서버에 붙인다: 저장(world/snw/nations.json), [NationApi] 구현, 바닐라 팀 동기화(2-1).
 */
object Nations : NationApi {
    val registry = NationRegistry()
    var server: MinecraftServer? = null
        private set
    private var dirty = false
    private var tickCounter = 0
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private val claimListeners = CopyOnWriteArrayList<(NationId, Set<ChunkKey>, Set<ChunkKey>) -> Unit>()
    private val relationListeners = CopyOnWriteArrayList<(NationId, NationId, Relation, Relation) -> Unit>()
    private val killListeners = CopyOnWriteArrayList<(KillRecord) -> Unit>()
    private val busListeners = CopyOnWriteArrayList<Pair<String?, (ServerEvent) -> Unit>>()

    fun register() {
        NationApi.install(this)
        // 다른 모드(zomApo)가 SERVER_STARTED 에서 바로 읽을 수 있게 데이터는 시작 전에 읽는다. 팀은 스코어보드가 생긴 뒤에.
        ServerLifecycleEvents.SERVER_STARTING.register { s -> server = s; load(s) }
        ServerLifecycleEvents.SERVER_STARTED.register { s -> syncTeams(s) }
        ServerLifecycleEvents.SERVER_STOPPING.register { s -> save(s) }
        ServerPlayConnectionEvents.JOIN.register { h, _, s -> syncPlayer(s, h.player) }
        ServerTickEvents.END_SERVER_TICK.register { s ->
            if (++tickCounter % 200 == 0 && dirty) save(s)        // 10초마다 바뀐 것만 저장
        }
    }

    fun markDirty() { dirty = true }

    private fun file(s: MinecraftServer) = s.getWorldPath(LevelResource.ROOT).resolve("snw").resolve("nations.json")

    private fun load(s: MinecraftServer) {
        val f = file(s)
        if (Files.exists(f)) {
            runCatching { registry.restore(gson.fromJson(Files.readString(f), NationRegistry.Snapshot::class.java)) }
                .onFailure { Snw.log.error("[simpleNationWar] nations.json 을 읽지 못했습니다", it) }
        }
        for ((id, name, color) in NationRegistry.DEFAULTS) registry.define(id, name, color)
    }

    fun save(s: MinecraftServer) {
        val f = file(s)
        Files.createDirectories(f.parent)
        val tmp = f.resolveSibling("nations.json.tmp")
        Files.writeString(tmp, gson.toJson(registry.snapshot()))
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        dirty = false
    }

    // ---- 바닐라 팀 ----
    fun teamName(id: String) = "snw_$id"

    private fun legacyColor(rgb: Int): TeamColor = when (rgb) {
        0xFF0045 -> TeamColor.RED
        0x3355BB -> TeamColor.BLUE
        0xFFCC11 -> TeamColor.YELLOW
        else -> TeamColor.entries.filter { it.textColor() != null }.minBy {
            val c = it.textColor()!!.value
            val dr = (c shr 16 and 255) - (rgb shr 16 and 255); val dg = (c shr 8 and 255) - (rgb shr 8 and 255); val db = (c and 255) - (rgb and 255)
            dr * dr + dg * dg + db * db
        }
    }

    fun team(s: MinecraftServer, n: NationRegistry.Nation): PlayerTeam {
        val sb = s.scoreboard
        val t = sb.getPlayerTeam(teamName(n.id)) ?: sb.addPlayerTeam(teamName(n.id))
        t.setDisplayName(Component.literal(n.name))
        t.setColor(Optional.of(legacyColor(n.colorRgb)))
        t.setPlayerPrefix(Component.literal("[${n.name}] ").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(n.colorRgb))))
        t.setAllowFriendlyFire(true)            // 2026-11-22: 같은 국가끼리도 싸울 수 있다
        t.setSeeFriendlyInvisibles(true)
        return t
    }

    fun syncTeams(s: MinecraftServer) {
        for (n in registry.nations.values) {
            val t = team(s, n)
            if (n.fallen) { t.players.toList().forEach { s.scoreboard.removePlayerFromTeam(it, t) }; continue }
            for (m in n.members) nameOf(s, m)?.let { if (s.scoreboard.getPlayersTeam(it) !== t) s.scoreboard.addPlayerToTeam(it, t) }
        }
    }

    fun syncPlayer(s: MinecraftServer, p: ServerPlayer) {
        val n = registry.nationOf(p.uuid)
        val name = p.scoreboardName
        if (n == null || n.fallen) { s.scoreboard.getPlayersTeam(name)?.let { if (it.name.startsWith("snw_")) s.scoreboard.removePlayerFromTeam(name, it) }; return }
        val t = team(s, n)
        if (s.scoreboard.getPlayersTeam(name) !== t) s.scoreboard.addPlayerToTeam(name, t)
    }

    fun nameOf(s: MinecraftServer, id: UUID): String? =
        s.playerList.getPlayer(id)?.scoreboardName ?: s.services().nameToIdCache().get(id).map { it.name() }.orElse(null)

    fun idOf(s: MinecraftServer, name: String): UUID? =
        s.playerList.getPlayerByName(name)?.uuid ?: s.services().nameToIdCache().get(name).map { it.id() }.orElse(null)

    // ---- 변경(이벤트를 붙여서) ----
    fun assignAndSync(player: UUID, nationId: String?, role: Role = Role.CITIZEN) {
        registry.assign(player, nationId, role); markDirty()
        server?.let { s -> s.playerList.getPlayer(player)?.let { syncPlayer(s, it) } ?: syncTeams(s) }
    }

    fun claimAndNotify(nationId: String, chunks: Collection<ChunkKey>): Set<ChunkKey> {
        val added = chunks.filter { registry.claim(nationId, it) }.toSet()
        if (added.isNotEmpty()) { markDirty(); claimListeners.forEach { it(NationId(nationId), added, emptySet()) } }
        return added
    }

    fun unclaimAndNotify(chunks: Collection<ChunkKey>): Map<String, Set<ChunkKey>> {
        val removed = HashMap<String, MutableSet<ChunkKey>>()
        for (c in chunks) registry.unclaim(c)?.let { removed.getOrPut(it) { linkedSetOf() } += c }
        if (removed.isNotEmpty()) { markDirty(); removed.forEach { (n, set) -> claimListeners.forEach { it(NationId(n), emptySet(), set) } } }
        return removed
    }

    fun notifyRelation(a: String, b: String, from: Relation, to: Relation) {
        markDirty()
        if (from != to) relationListeners.forEach { it(NationId(a), NationId(b), from, to) }
    }

    fun notifyKill(r: KillRecord) = killListeners.forEach { it(r) }

    // ---- NationApi ----
    private fun view(n: NationRegistry.Nation) = NationView(NationId(n.id), n.name, n.colorRgb, n.leader, n.members.toSet(),
        n.core?.chunk)

    override fun nations(): List<NationView> = registry.alive().map(::view)
    override fun nationOf(player: UUID): NationView? = registry.nationOf(player)?.takeIf { !it.fallen }?.let(::view)
    override fun sameNation(a: UUID, b: UUID): Boolean = registry.sameNation(a, b)
    override fun allied(a: UUID, b: UUID): Boolean = registry.allied(a, b)
    override fun ownerOf(chunk: ChunkKey): NationView? = registry.ownerOf(chunk)?.let(::view)
    override fun territory(nation: NationId): Set<ChunkKey> = registry.territory(nation.value)
    override fun nickname(player: UUID): String? = dev.menonng.snw.nickname.Nicknames.get(player)
    /** 봇(snw-discord)이 검사를 마친 닉네임을 넣는다. */
    override fun setNickname(player: UUID, nickname: String?) = dev.menonng.snw.nickname.Nicknames.apply(server, player, nickname)
    override fun assign(player: UUID, nation: NationId?, role: Role) = assignAndSync(player, nation?.value, role)

    private val eventsImpl = object : NationEvents {
        override fun onClaimChanged(listener: (NationId, Set<ChunkKey>, Set<ChunkKey>) -> Unit) { claimListeners += listener }
        override fun onRelationChanged(listener: (NationId, NationId, Relation, Relation) -> Unit) { relationListeners += listener }
        override fun onPlayerKill(listener: (KillRecord) -> Unit) { killListeners += listener }
    }
    override fun events(): NationEvents = eventsImpl

    private val busImpl = object : ServerEventBus {
        override fun publish(event: ServerEvent) { for ((type, l) in busListeners) if (type == null || type == event.type) runCatching { l(event) } }
        override fun subscribe(type: String?, listener: (ServerEvent) -> Unit) { busListeners += type to listener }
    }
    override fun eventBus(): ServerEventBus = busImpl

    override fun relation(a: NationId, b: NationId): Relation = registry.relation(a.value, b.value, System.currentTimeMillis())

    fun coreView(n: NationRegistry.Nation): CoreView? = n.core?.let {
        CoreView(NationId(n.id), it.dimension, it.x, it.y, it.z, registry.coreHp(n, System.currentTimeMillis()), dev.menonng.snw.logic.core.NationCore.MAX_HP)
    }
    override fun core(nation: NationId): CoreView? = registry.nation(nation.value)?.takeIf { !it.fallen }?.let(::coreView)

    /** 블러드문 휴전 상태(zomApo 가 켠다). */
    @Volatile var truceActive = false
        private set
    @Volatile var truceZones: List<CoreView> = emptyList()
        private set
    var truceRadius = 128.0
        private set
    override fun setTruce(active: Boolean, defenseZones: List<CoreView>, radius: Double) {
        truceActive = active; truceZones = if (active) defenseZones else emptyList(); truceRadius = radius
    }
    override fun truce(): Boolean = truceActive

    /** 잠수 판정은 [dev.menonng.snw.protect.Afk] 가 넣는다. */
    var afkCheck: (UUID) -> Boolean = { false }
    override fun isAfk(player: UUID): Boolean = afkCheck(player)

    override fun markProtected(player: UUID, untilEpochMillis: Long) = dev.menonng.snw.protect.Protection.markProtected(player, untilEpochMillis)
    override fun grantJoinProtection(player: UUID): Boolean = dev.menonng.snw.protect.Protection.combat.grantJoinProtection(player)

    override fun landmarks(): List<dev.menonng.snw.api.LandmarkView> = dev.menonng.snw.landmark.Landmarks.state.landmarks.values.map {
        dev.menonng.snw.api.LandmarkView(it.id, it.name, it.dim, it.x, it.y, it.z, it.owner?.let(::NationId), it.kind == dev.menonng.snw.landmark.Landmarks.Kind.DEEP)
    }

    var infectionLevel: ((String, Int, Int) -> Int)? = null
        private set
    override fun setInfectionLevelSource(source: (String, Int, Int) -> Int) { infectionLevel = source }

    var zombieScore: (UUID) -> Double = { 0.0 }
        private set
    override fun setZombieScoreSource(source: (UUID) -> Double) { zombieScore = source }

    fun publish(type: String, data: Map<String, Any?>) = busImpl.publish(ServerEvent(type, System.currentTimeMillis(), data))
}
