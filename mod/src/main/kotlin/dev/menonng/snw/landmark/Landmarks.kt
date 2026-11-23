package dev.menonng.snw.landmark

import com.google.gson.GsonBuilder
import dev.menonng.snw.api.Relation
import dev.menonng.snw.logic.state.NationRegistry
import dev.menonng.snw.logic.territory.Chunk
import dev.menonng.snw.nation.NationCommands
import dev.menonng.snw.nation.NationGui
import dev.menonng.snw.nation.Nations
import dev.menonng.snw.nation.Treasury
import dev.menonng.snw.ui.Gui
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.BossEvent
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * 거점전(nation.md 8절). 접경 거점 ×3 + 심층 거점 ×1(운영자가 폐허를 짓고 `/snw landmark` 로 지정).
 * - 거점전 시간(기본 목 21~22시, 일 20~21시, KST)에만 소유권이 바뀐다. 점령 구역(반경 8) 안에 다른 국가보다 많으면 진행, 누적 5분이면 점령.
 * - 인원이 같거나 소유국(동맹 포함)이 같거나 많으면 분쟁(정지). 동맹 국민은 인원에 함께 세고 소유권은 한 국가에만.
 * - 2:1 고착 방지: 다른 두 국가가 동맹이면 고립 국가 점령 속도 ×1.25. 거점전 동안 주변에 좀비가 몰려온다(PvPvE).
 * - 보상: 매일 06:00 거점 상자에 자원(소유국 국민만 연다), 영토 상한 +8. 첫 점령은 국고 칸 해금.
 */
object Landmarks {
    val zone: ZoneId = ZoneId.of("Asia/Seoul")
    const val CAPTURE_RADIUS = 8.0
    const val CAPTURE_SECONDS = 300.0
    const val PVP_RADIUS = 32.0
    const val ISOLATED_BONUS = 1.25

    enum class Kind(val label: String) { BORDER("접경 거점"), DEEP("심층 거점") }
    data class Landmark(
        val id: String, var name: String, var kind: Kind, val dim: String, val x: Int, val y: Int, val z: Int,
        var owner: String? = null, var ownedSince: Long = 0L, val progress: MutableMap<String, Double> = linkedMapOf(),
        var lastFill: String? = null, var openedToday: Boolean = false,
    )
    data class State(val landmarks: MutableMap<String, Landmark> = linkedMapOf(), var forced: Boolean? = null)

    var state = State()
        private set
    private val bars = HashMap<String, ServerBossEvent>()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private fun file(s: MinecraftServer) = s.getWorldPath(LevelResource.ROOT).resolve("snw").resolve("landmarks.json")
    private var wasOpen = false

    /** 거점전 시간(설정): 요일 → 시작 시각, 1시간. */
    val schedule = mapOf(DayOfWeek.THURSDAY to 21, DayOfWeek.SUNDAY to 20)

    fun isWar(now: LocalDateTime = LocalDateTime.now(zone)): Boolean = state.forced ?: (schedule[now.dayOfWeek] == now.hour)

    fun nextWar(now: LocalDateTime = LocalDateTime.now(zone)): LocalDateTime {
        var d = now.toLocalDate()
        repeat(8) {
            val h = schedule[d.dayOfWeek]
            if (h != null) { val t = d.atTime(h, 0); if (t.plusHours(1).isAfter(now)) return t }
            d = d.plusDays(1)
        }
        return now
    }

    fun owned(nation: String): Int = state.landmarks.values.count { it.owner == nation }

    fun chunks(dim: String): Set<Chunk> = state.landmarks.values.filter { it.dim == dim }.flatMap { l ->
        val cx = l.x shr 4; val cz = l.z shr 4
        (-1..1).flatMap { dx -> (-1..1).map { dz -> Chunk(cx + dx, cz + dz) } }
    }.toSet()

    private fun levelOf(s: MinecraftServer, dim: String): ServerLevel? = s.allLevels.firstOrNull { it.dimension().identifier().toString() == dim }

    // ---- 운영자 ----
    fun define(level: ServerLevel, id: String, name: String, kind: Kind, pos: BlockPos): Landmark {
        val l = Landmark(id, name, kind, level.dimension().identifier().toString(), pos.x, pos.y, pos.z)
        state.landmarks[id] = l
        level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState())
        save(level.server)
        return l
    }

    fun setOwner(s: MinecraftServer, l: Landmark, nation: String?) {
        val before = l.owner
        l.owner = nation; l.ownedSince = System.currentTimeMillis(); l.progress.clear()
        if (nation != null && nation != before) Treasury.discover(s, nation, "landmark")
        save(s)
    }

    // ---- 점령 ----
    private fun second(s: MinecraftServer) {
        val war = isWar()
        if (war != wasOpen) {
            wasOpen = war
            NationCommands.broadcast(s, if (war) "거점전이 시작되었습니다. 1시간 동안 거점 소유권이 바뀔 수 있습니다." else "거점전이 끝났습니다.", 0xFF7E00)
            Nations.publish(if (war) "war_window_start" else "war_window_end", emptyMap())
            if (!war) { bars.values.forEach { it.removeAllPlayers() }; state.landmarks.values.forEach { it.progress.clear() }; save(s) }
        }
        if (!war) return
        val now = System.currentTimeMillis()
        for (l in state.landmarks.values) {
            val level = levelOf(s, l.dim) ?: continue
            val center = net.minecraft.world.phys.Vec3(l.x + 0.5, l.y.toDouble(), l.z + 0.5)
            val present = level.players().filter { it.isAlive && !it.isSpectator && it.position().distanceTo(center) <= CAPTURE_RADIUS && !Nations.afkCheck(it.uuid) }
            val own = present.groupingBy { Nations.registry.nationOf(it.uuid)?.id }.eachCount().filterKeys { it != null }.mapKeys { it.key!! }
            val step = dev.menonng.snw.logic.landmark.Capture.step(own, Nations.registry.alive().map { it.id }, { Nations.registry.allyOf(it) }, l.owner, ISOLATED_BONUS)
            val leader = step.leader
            val contested = step.contested
            val bar = bars.getOrPut(l.id) { ServerBossEvent(UUID.randomUUID(), Component.literal(l.name), BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS) }
            if (!contested && leader != null) {
                val p = (l.progress[leader] ?: 0.0) + step.speed
                l.progress[leader] = p
                if (p >= CAPTURE_SECONDS) {
                    val from = l.owner?.let { Nations.registry.nation(it)?.name } ?: "없음"
                    setOwner(s, l, leader)
                    val to = Nations.registry.nation(leader)!!
                    NationCommands.broadcast(s, "${to.name}이(가) ${l.name}을(를) 점령했습니다. (이전 소유: $from)", to.colorRgb)
                    Nations.publish("landmark_captured", mapOf("landmark" to l.name, "nation" to to.name))
                }
                val n = Nations.registry.nation(leader)
                bar.name = Component.literal("${l.name} · ${n?.name ?: "?"} 점령 중 ${fmt(l.progress[leader] ?: 0.0)} / 5:00")
                bar.progress = ((l.progress[leader] ?: 0.0) / CAPTURE_SECONDS).toFloat().coerceIn(0f, 1f)
            } else {
                val ownerName = l.owner?.let { Nations.registry.nation(it)?.name } ?: "주인 없음"
                bar.name = Component.literal(if (present.isEmpty()) "${l.name} · $ownerName" else "${l.name} · 분쟁 중")
                bar.progress = 1f
            }
            for (p in s.playerList.players) if (p.level() === level && p.position().distanceTo(center) <= 48) bar.addPlayer(p) else bar.removePlayer(p)
            // PvPvE: 점령 구역 주변으로 좀비가 몰려온다(10초마다 2마리, 32블록 안 20마리까지)
            if (now / 1000 % 10 == 0L && level.isLoaded(BlockPos(l.x, l.y, l.z))) spawnZombies(level, l)
        }
    }

    private fun fmt(sec: Double) = "%d:%02d".format(sec.toInt() / 60, sec.toInt() % 60)

    private fun spawnZombies(level: ServerLevel, l: Landmark) {
        val near = level.getEntitiesOfClass(Zombie::class.java, net.minecraft.world.phys.AABB(BlockPos(l.x, l.y, l.z)).inflate(32.0)).size
        if (near >= 20) return
        repeat(2) {
            val a = level.random.nextDouble() * Math.PI * 2
            val r = 16 + level.random.nextDouble() * 8
            val x = (l.x + Math.cos(a) * r).toInt(); val z = (l.z + Math.sin(a) * r).toInt()
            val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
            val pos = BlockPos(x, y, z)
            if (!level.getBlockState(pos.below()).isSolid || !level.getBlockState(pos).isAir) return@repeat
            val zmb = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.NATURAL) ?: return@repeat
            zmb.snapTo(x + 0.5, y.toDouble(), z + 0.5, 0f, 0f)
            zmb.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.NATURAL, null)
            zmb.navigation.moveTo(l.x + 0.5, l.y.toDouble(), l.z + 0.5, 1.0)
            level.addFreshEntity(zmb)
        }
    }

    // ---- 보상 상자 ----
    private fun fill(s: MinecraftServer) {
        val today = LocalDate.now(zone).toString()
        for (l in state.landmarks.values) {
            if (l.lastFill == today || l.owner == null) continue
            val level = levelOf(s, l.dim) ?: continue
            level.getChunk(l.x shr 4, l.z shr 4)
            val pos = BlockPos(l.x, l.y, l.z)
            if (!level.getBlockState(pos).`is`(Blocks.CHEST)) level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState())
            val chest = level.getBlockEntity(pos) as? ChestBlockEntity ?: continue
            val r = level.random
            val deep = l.kind == Kind.DEEP
            val loot = mutableListOf(ItemStack(Items.IRON_INGOT, if (deep) 16 else 8), ItemStack(Items.GOLD_INGOT, if (deep) 8 else 4))
            if (r.nextDouble() < (if (deep) 0.6 else 0.25)) loot += ItemStack(Items.DIAMOND, 1 + r.nextInt(if (deep) 3 else 2))
            for ((i, st) in loot.withIndex()) chest.setItem(i, st)
            l.lastFill = today; l.openedToday = false
            Nations.publish("landmark_chest", mapOf("dimension" to l.dim, "x" to l.x, "y" to l.y, "z" to l.z, "deep" to deep))   // zomApo 가 바이러스 결정을 넣는다
        }
        save(s)
    }

    private fun at(level: ServerLevel, pos: BlockPos): Landmark? {
        val d = level.dimension().identifier().toString()
        return state.landmarks.values.firstOrNull { it.dim == d && it.x == pos.x && it.y == pos.y && it.z == pos.z }
    }

    /** 국가 창 거점 탭. */
    private fun tab(gui: Gui, p: ServerPlayer, n: NationRegistry.Nation) {
        val now = System.currentTimeMillis()
        val next = nextWar()
        state.landmarks.values.take(7).forEachIndexed { i, l ->
            val o = l.owner?.let { Nations.registry.nation(it) }
            val since = if (o != null) "${(now - l.ownedSince) / 3_600_000}시간째" else "—"
            gui.set(28 + i, Gui.item(if (o != null) Gui.banner(o.colorRgb) else Items.BANNER.lightGray(), Gui.text(l.name, o?.let { Gui.rgb(it.colorRgb) }), listOf(
                Gui.gray(l.kind.label), Gui.gray("소유: ${o?.name ?: "없음"} ($since)"),
                Gui.gray(if (isWar()) "지금 거점전 중" else "다음 거점전 ${next.monthValue}/${next.dayOfMonth} ${next.hour}:00"),
                Gui.gray(if (l.owner == n.id) (if (l.openedToday) "오늘 보상 상자를 열었습니다" else "오늘 보상 상자가 남아 있습니다") else "보상 상자는 소유국만 엽니다"),
            ), hide = setOf(net.minecraft.core.component.DataComponents.BANNER_PATTERNS)))
        }
        if (state.landmarks.isEmpty()) gui.set(31, Gui.item(Items.LODESTONE, Gui.text("아직 지정된 거점이 없습니다")))
    }

    fun load(s: MinecraftServer) { val f = file(s); if (Files.exists(f)) runCatching { state = gson.fromJson(Files.readString(f), State::class.java) } }
    fun save(s: MinecraftServer) { val f = file(s); Files.createDirectories(f.parent); Files.writeString(f, gson.toJson(state)) }

    fun register() {
        ServerLifecycleEvents.SERVER_STARTING.register { s -> load(s) }
        ServerLifecycleEvents.SERVER_STOPPING.register { s -> save(s) }
        NationCommands.landmarkChunks = { chunks(it) }
        NationCommands.landmarksOwned = { owned(it) }
        NationGui.landmarkTab = { g, p, n -> tab(g, p, n) }
        var t = 0
        ServerTickEvents.END_SERVER_TICK.register { s ->
            if (++t % 20 == 0) second(s)
            if (t % 1200 == 0 && LocalDateTime.now(zone).hour >= 6) fill(s)
        }
        // 거점 상자: 소유국 국민만 연다, 부서지지 않는다
        UseBlockCallback.EVENT.register { player, level, _, hit ->
            if (level !is ServerLevel || player !is ServerPlayer) return@register InteractionResult.PASS
            val l = at(level, hit.blockPos) ?: return@register InteractionResult.PASS
            if (Nations.registry.nationOf(player.uuid)?.id != l.owner) { player.sendSystemMessage(Gui.text("${l.name}의 보상 상자는 소유국 국민만 엽니다."), true); InteractionResult.FAIL }
            else { l.openedToday = true; InteractionResult.PASS }
        }
        PlayerBlockBreakEvents.BEFORE.register { level, player, pos, _, _ ->
            if (level !is ServerLevel) return@register true
            val l = at(level, pos) ?: return@register true
            if (player.isCreative && net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check((player as ServerPlayer).permissions())) true
            else { (player as? ServerPlayer)?.sendSystemMessage(Gui.text("${l.name}의 보상 상자는 부서지지 않습니다."), true); false }
        }
    }
}
