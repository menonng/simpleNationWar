package dev.menonng.snw.season

import com.google.gson.GsonBuilder
import dev.menonng.snw.Snw
import dev.menonng.snw.api.Role
import dev.menonng.snw.core.CoreSystem
import dev.menonng.snw.logic.join.KitTier
import dev.menonng.snw.logic.join.LateJoin
import dev.menonng.snw.logic.spawn.PersonalSpawn
import dev.menonng.snw.logic.spawn.XYZ
import dev.menonng.snw.logic.spawn.XZ
import dev.menonng.snw.logic.state.NationRegistry
import dev.menonng.snw.nation.NationCommands
import dev.menonng.snw.nation.Nations
import dev.menonng.snw.protect.Protection
import dev.menonng.snw.ui.Gui
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BlockTags
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.storage.LevelData
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.random.Random

/**
 * 시즌 운영(nation.md 3·3-1·3-2·4·5-1).
 * - 시즌 전: 모두 생존자 캠프(모험 모드, 피해·허기 없음).
 * - `/snw season start`: 국가마다 개인 스폰 지점(국가 스폰 지점 40~60블록)을 정해 그 자리로 보내고 바닐라 리스폰 지점으로 둔다. 서바이벌, locator_bar 끔.
 * - 늦게 온 사람: 국가 선택 창(본인이 고른다), 3일째 이후 처음 접속이면 늦깎이 보급.
 * - 이적 창(운영자가 연다): 본인 신청 + 양쪽 원수 동의. 원수가 72시간 넘게 없으면 접속 시간이 가장 많은 간부(없으면 국민)가 임시 원수.
 * - 수도 이전: 시즌 시작 72시간 안에 원수가 1번(처음 스폰 지점 250블록 안, 다른 수도에서 400블록 밖).
 */
object Season {
    val zone: ZoneId = ZoneId.of("Asia/Seoul")
    private const val HOUR = 3_600_000L

    data class Spot(val dim: String, val x: Int, val y: Int, val z: Int)
    data class State(
        var started: Boolean = false,
        var startMillis: Long = 0L,
        var transferUntil: Long = 0L,
        val firstJoin: MutableMap<String, Long> = linkedMapOf(),
        val lastSeen: MutableMap<String, Long> = linkedMapOf(),
        val playMinutes: MutableMap<String, Int> = linkedMapOf(),
        val personalSpawn: MutableMap<String, Spot> = linkedMapOf(),
        val kitGiven: MutableSet<String> = linkedSetOf(),
        /** 처음 국가 스폰 지점(수도 이전 반경 판정용). */
        val originSpawn: MutableMap<String, Spot> = linkedMapOf(),
        /** 이적 신청: 플레이어 → (가려는 국가, 원래 원수 동의, 받는 원수 동의). */
        val transfers: MutableMap<String, Triple<String, Boolean, Boolean>> = linkedMapOf(),
        var imbalanceSince: Long = 0L,
    )

    var state = State()
        private set
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private fun file(s: MinecraftServer) = s.getWorldPath(LevelResource.ROOT).resolve("snw").resolve("season.json")

    fun load(s: MinecraftServer) { val f = file(s); if (Files.exists(f)) runCatching { state = gson.fromJson(Files.readString(f), State::class.java) } }
    fun save(s: MinecraftServer) { val f = file(s); Files.createDirectories(f.parent); Files.writeString(f, gson.toJson(state)) }

    fun seasonDay(now: Long = System.currentTimeMillis()): Int =
        if (!state.started) 0 else ((now - state.startMillis) / (24 * HOUR)).toInt()

    private fun isOp(p: ServerPlayer) = net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(p.permissions())

    // ---- 원수 부재(4절) ----
    /** 지금 원수 권한을 가진 사람: 원수가 72시간 넘게 접속하지 않았으면 접속 시간이 가장 많은 간부(없으면 국민). */
    fun effectiveLeader(n: NationRegistry.Nation, now: Long = System.currentTimeMillis()): UUID? {
        val leader = n.leader ?: return n.members.maxByOrNull { state.playMinutes[it.toString()] ?: 0 }
        val seen = state.lastSeen[leader.toString()] ?: return leader
        if (Nations.server?.playerList?.getPlayer(leader) != null || now - seen < 72 * HOUR) return leader
        val pool = n.officers.ifEmpty { n.members - leader }
        return pool.maxByOrNull { state.playMinutes[it.toString()] ?: 0 } ?: leader
    }

    // ---- 개인 스폰 지점(3-1) ----
    private fun levelOf(s: MinecraftServer, dim: String): ServerLevel? = s.allLevels.firstOrNull { it.dimension().identifier().toString() == dim }

    private fun surfaceY(level: ServerLevel, nationId: String, x: Int, z: Int): Int? {
        level.getChunk(x shr 4, z shr 4)
        val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
        val pos = BlockPos(x, y, z)
        val floor = level.getBlockState(pos.below())
        if (!floor.isSolid || floor.`is`(BlockTags.LEAVES) || !level.getFluidState(pos.below()).isEmpty || !level.getFluidState(pos).isEmpty) return null
        if (floor.`is`(Blocks.MAGMA_BLOCK) || floor.`is`(Blocks.CACTUS)) return null
        if (!level.getBlockState(pos).isAir || !level.getBlockState(pos.above()).isAir) return null
        for (dx in -1..1) for (dz in -1..1) if (level.getBlockState(pos.offset(dx, 0, dz)).`is`(Blocks.CACTUS)) return null
        val owner = Nations.registry.ownerOf(dev.menonng.snw.api.ChunkKey(level.dimension().identifier().toString(), x shr 4, z shr 4))
        if (owner != null && owner.id != nationId) return null
        return y
    }

    fun pickPersonal(s: MinecraftServer, n: NationRegistry.Nation, p: UUID, random: Random = Random.Default): Spot? {
        val sx = n.spawnX ?: return null; val sz = n.spawnZ ?: return null
        val level = s.overworld()
        val fy = surfaceY(level, n.id, sx, sz) ?: level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz)
        val r = PersonalSpawn.pick(random, XZ(sx.toDouble(), sz.toDouble()), XYZ(sx, fy, sz)) { x, z -> surfaceY(level, n.id, x, z) }
        return Spot("minecraft:overworld", r.x, r.y, r.z).also { state.personalSpawn[p.toString()] = it }
    }

    private fun sendTo(p: ServerPlayer, spot: Spot) {
        val s = p.level().server
        val level = levelOf(s, spot.dim) ?: return
        p.teleportTo(level, spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5, emptySet(), p.yRot, 0f, true)
        setRespawn(p, spot)
    }

    private fun setRespawn(p: ServerPlayer, spot: Spot) {
        val level = levelOf(p.level().server, spot.dim) ?: return
        p.setRespawnPosition(ServerPlayer.RespawnConfig(LevelData.RespawnData(GlobalPos.of(level.dimension(), BlockPos(spot.x, spot.y, spot.z)), 0f, 0f), true), false)
    }

    private fun camp(p: ServerPlayer) {
        if (isOp(p) && p.isCreative) return
        p.setGameMode(GameType.ADVENTURE)
        p.addEffect(MobEffectInstance(MobEffects.SATURATION, 20 * 60 * 60, 0, false, false))
    }

    /** 시즌 시작 명령. */
    fun start(s: MinecraftServer): String {
        if (state.started) return "시즌이 이미 시작되었습니다."
        val missing = Nations.registry.alive().filter { it.spawnX == null }
        if (missing.isNotEmpty()) return "국가 스폰 지점이 없는 국가가 있습니다: ${missing.joinToString { it.name }}. /snw spawn set 으로 먼저 지정하십시오."
        state.started = true; state.startMillis = System.currentTimeMillis()
        for (n in Nations.registry.alive()) state.originSpawn[n.id] = Spot("minecraft:overworld", n.spawnX!!, 0, n.spawnZ!!)
        val ow = s.overworld()
        ow.gameRules.set(GameRules.LOCATOR_BAR, false, s)
        var moved = 0
        for (p in s.playerList.players) {
            val n = Nations.registry.nationOf(p.uuid)?.takeIf { !it.fallen } ?: continue
            pickPersonal(s, n, p.uuid)?.let { sendTo(p, it) }
            p.setGameMode(GameType.SURVIVAL); p.removeEffect(MobEffects.SATURATION)
            state.firstJoin.putIfAbsent(p.uuid.toString(), state.startMillis)
            moved++
        }
        // 오프라인 국민도 개인 스폰 지점을 미리 정한다
        for (n in Nations.registry.alive()) for (m in n.members) if (!state.personalSpawn.containsKey(m.toString())) pickPersonal(s, n, m)
        save(s)
        NationCommands.broadcast(s, "시즌이 시작되었습니다. 각자 국가 스폰 지점 주변에서 시작합니다. 이 자리가 리스폰 지점입니다.", 0x55BB44)
        Nations.publish("season_start", mapOf("date" to LocalDate.now(zone).toString()))
        return "시즌을 시작했습니다. 접속 중인 ${moved}명을 국가 스폰 지점 주변으로 보냈습니다."
    }

    fun status(s: MinecraftServer): String {
        val now = System.currentTimeMillis()
        val head = if (!state.started) "시즌 시작 전" else "시즌 ${seasonDay(now) + 1}일째"
        val lines = Nations.registry.alive().map { n ->
            val online = n.members.count { s.playerList.getPlayer(it) != null }
            "${n.name}: 국민 ${n.members.size}명, 접속 $online, 최근 72시간 활동 ${active72(n, now)}명, 원수 ${effectiveLeader(n, now)?.let { Nations.nameOf(s, it) } ?: "없음"}"
        }
        val tw = if (state.transferUntil > now) " · 이적 창 ${(state.transferUntil - now) / HOUR}시간 남음" else ""
        return (listOf(head + tw) + lines).joinToString("\n")
    }

    fun active72(n: NationRegistry.Nation, now: Long): Int = n.members.count { Protection.afk.activeMillis(it, now, 72) >= HOUR }

    // ---- 접속 ----
    private fun onJoin(s: MinecraftServer, p: ServerPlayer) {
        val now = System.currentTimeMillis()
        val id = p.uuid.toString()
        val firstEver = !state.firstJoin.containsKey(id)
        state.firstJoin.putIfAbsent(id, now)
        state.lastSeen[id] = now
        if (!state.started) { camp(p); return }
        val n = Nations.registry.nationOf(p.uuid)?.takeIf { !it.fallen }
        if (n == null) { camp(p); s.execute { openChooser(p) }; return }
        if (!state.personalSpawn.containsKey(id)) {
            pickPersonal(s, n, p.uuid)?.let { sendTo(p, it) }
            p.setGameMode(GameType.SURVIVAL); p.removeEffect(MobEffects.SATURATION)
        } else if (p.gameMode() == GameType.ADVENTURE && !(isOp(p) && p.isCreative)) {
            p.setGameMode(GameType.SURVIVAL); p.removeEffect(MobEffects.SATURATION)
        }
        maybeKit(s, p, firstEver)
    }

    private fun maybeKit(s: MinecraftServer, p: ServerPlayer, firstEver: Boolean) {
        val id = p.uuid.toString()
        if (id in state.kitGiven) return
        val tier = LateJoin.tier(seasonDay(), firstEver || (state.firstJoin[id] ?: 0) >= state.startMillis + 3 * 24 * HOUR)
        if (tier == KitTier.NONE) return
        state.kitGiven += id
        for ((item, count) in LateJoin.kit(tier)) {
            if (item.startsWith("zomapo:")) {
                if (FabricLoader.getInstance().isModLoaded("zomapo")) runCatching {
                    s.commands.performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), "zomapo give ${p.scoreboardName} ${item.removePrefix("zomapo:")} $count")
                }
                continue
            }
            val it = BuiltInRegistries.ITEM.getValue(Identifier.parse(item))
            if (it == Items.AIR) continue
            p.inventory.placeItemBackInInventory(ItemStack(it, count), net.minecraft.util.Prediction.SERVER_ONLY)
        }
        p.sendSystemMessage(Gui.text("늦게 합류한 분께 늦깎이 보급품을 드렸습니다. 따라잡는 데 도움이 되기를 바랍니다.", Gui.rgb(0x55BB44)))
    }

    /** 국가 선택 창(3-2): 이름·색·원수·국민 수·최근 72시간 활동 국민 수. */
    fun openChooser(p: ServerPlayer) {
        val s = p.level().server
        val now = System.currentTimeMillis()
        val gui = Gui(Gui.text("국가를 고르십시오"))
        val nations = Nations.registry.alive()
        val slots = listOf(20, 22, 24)
        nations.take(3).forEachIndexed { i, n ->
            val leader = effectiveLeader(n, now)?.let { Nations.nameOf(s, it) } ?: "없음"
            gui.set(slots[i], Gui.item(Gui.banner(n.colorRgb), Gui.text(n.name, Gui.rgb(n.colorRgb)), listOf(
                Gui.gray("원수 $leader"), Gui.gray("국민 ${n.members.size}명"), Gui.gray("최근 72시간 활동 국민 ${active72(n, now)}명"), Gui.text(""), Gui.gray("눌러서 이 국가에 들어갑니다"),
            ))) { pl -> choose(pl, n) }
        }
        gui.set(4, Gui.item(Items.BOOK, Gui.text("늦게 오신 분은 직접 국가를 고릅니다"), listOf(Gui.gray("고르기 전에는 생존자 캠프에 머뭅니다."), Gui.gray("창을 닫으면 다음 접속 때 다시 뜹니다."))))
        gui.fillBorder()
        gui.open(p)
    }

    private fun choose(p: ServerPlayer, n: NationRegistry.Nation) {
        if (Nations.registry.nationOf(p.uuid) != null) { p.closeContainer(); return }
        p.closeContainer()
        Nations.assignAndSync(p.uuid, n.id)
        val s = p.level().server
        pickPersonal(s, n, p.uuid)?.let { sendTo(p, it) }
        p.setGameMode(GameType.SURVIVAL); p.removeEffect(MobEffects.SATURATION)
        maybeKit(s, p, true)
        save(s)
        NationCommands.broadcast(s, "${p.scoreboardName}님이 ${n.name}에 합류했습니다.", n.colorRgb)
        Nations.publish("late_join", mapOf("nation" to n.name))
    }

    // ---- 리스폰: 침대·정박기가 없으면 개인 스폰 지점 ----
    private fun onRespawn(p: ServerPlayer) {
        if (!state.started) return
        val spot = state.personalSpawn[p.uuid.toString()] ?: return
        val cfg = p.respawnConfig
        val inCamp = Protection.inCamp(p.level(), p.x, p.z)
        if (cfg == null || inCamp) { sendTo(p, spot) }
    }

    // ---- 이적(3절) ----
    fun openTransfer(s: MinecraftServer, hours: Int) {
        state.transferUntil = System.currentTimeMillis() + hours * HOUR
        save(s)
        NationCommands.broadcast(s, "이적 창이 ${hours}시간 동안 열립니다. /n transfer <국가> 로 신청하면 양쪽 원수가 동의할 때 옮겨집니다.")
    }

    fun requestTransfer(p: ServerPlayer, to: NationRegistry.Nation): String {
        val now = System.currentTimeMillis()
        if (state.transferUntil <= now) return "지금은 이적 창이 열려 있지 않습니다."
        val from = Nations.registry.nationOf(p.uuid) ?: return "국가에 속해 있지 않습니다."
        if (from.id == to.id) return "이미 그 국가 국민입니다."
        state.transfers[p.uuid.toString()] = Triple(to.id, false, false)
        val s = p.level().server
        for (l in listOfNotNull(effectiveLeader(from, now), effectiveLeader(to, now))) s.playerList.getPlayer(l)?.sendSystemMessage(
            Gui.text("${p.scoreboardName}님이 ${from.name} → ${to.name} 이적을 신청했습니다. 동의하려면 /n transfer accept ${p.scoreboardName} 를 입력하십시오.", Gui.rgb(0xFF7E00)))
        return "이적을 신청했습니다. 양쪽 원수가 동의하면 옮겨집니다."
    }

    fun acceptTransfer(leader: ServerPlayer, playerName: String): String {
        val s = leader.level().server
        val now = System.currentTimeMillis()
        val pid = Nations.idOf(s, playerName) ?: return "그런 플레이어를 찾지 못했습니다."
        val req = state.transfers[pid.toString()] ?: return "그 플레이어의 이적 신청이 없습니다."
        val from = Nations.registry.nationOf(pid) ?: return "그 플레이어가 국가에 속해 있지 않습니다."
        val to = Nations.registry.nation(req.first) ?: return "가려는 국가가 없습니다."
        var (_, a, b) = req
        if (effectiveLeader(from, now) == leader.uuid) a = true
        if (effectiveLeader(to, now) == leader.uuid) b = true
        if (!a && !b) return "이 이적에 동의할 권한이 없습니다(원수만)."
        if (!(a && b)) { state.transfers[pid.toString()] = Triple(req.first, a, b); return "동의했습니다. 다른 쪽 원수의 동의를 기다립니다." }
        state.transfers.remove(pid.toString())
        Nations.assignAndSync(pid, to.id)
        state.personalSpawn.remove(pid.toString())
        s.playerList.getPlayer(pid)?.let { pl -> pickPersonal(s, to, pid)?.let { sendTo(pl, it) } } ?: pickPersonal(s, to, pid)
        save(s)
        NationCommands.broadcast(s, "$playerName 님이 ${from.name}에서 ${to.name}(으)로 이적했습니다.", to.colorRgb)
        return "이적이 이루어졌습니다."
    }

    // ---- 수도 이전(5-1) ----
    fun moveCapital(p: ServerPlayer): String {
        val now = System.currentTimeMillis()
        val n = Nations.registry.nationOf(p.uuid)?.takeIf { !it.fallen } ?: return "국가에 속해 있지 않습니다."
        if (effectiveLeader(n, now) != p.uuid) return "수도 이전은 원수만 할 수 있습니다."
        if (!state.started || now - state.startMillis > 72 * HOUR) return "수도 이전은 시즌 시작 72시간 안에만 할 수 있습니다."
        if (n.capitalMoved) return "수도는 한 번만 옮길 수 있습니다."
        val origin = state.originSpawn[n.id] ?: return "처음 국가 스폰 지점 기록이 없습니다."
        val x = p.blockX; val z = p.blockZ
        if (Math.hypot((x - origin.x).toDouble(), (z - origin.z).toDouble()) > 250) return "처음 국가 스폰 지점에서 250블록 안이어야 합니다."
        for (o in Nations.registry.alive()) if (o.id != n.id) o.core?.let { c -> if (Math.hypot((x - c.x).toDouble(), (z - c.z).toDouble()) < 400) return "다른 국가 수도에서 400블록 밖이어야 합니다." }
        CoreSystem.setCore(p.level(), n, p.blockPosition())
        n.spawnX = x; n.spawnZ = z; n.capitalMoved = true
        val s = p.level().server
        for (m in n.members) { state.personalSpawn.remove(m.toString()); pickPersonal(s, n, m) }
        for (m in n.members) s.playerList.getPlayer(m)?.let { pl -> state.personalSpawn[m.toString()]?.let { setRespawn(pl, it) } }
        Nations.markDirty(); save(s)
        NationCommands.broadcast(s, "${n.name}이(가) 수도를 옮겼습니다. 국민의 개인 스폰 지점도 새 수도 기준으로 다시 정했습니다.", n.colorRgb)
        return "수도를 옮겼습니다."
    }

    // ---- 매분: 접속 시간·불균형 감시 ----
    private fun minute(s: MinecraftServer) {
        val now = System.currentTimeMillis()
        for (p in s.playerList.players) {
            state.lastSeen[p.uuid.toString()] = now
            if (!Nations.afkCheck(p.uuid)) state.playMinutes.merge(p.uuid.toString(), 1, Int::plus)
        }
        if (!state.started) return
        val counts = Nations.registry.alive().map { active72(it, now) }
        if (counts.size >= 2 && counts.max() - counts.min() >= 2) {
            if (state.imbalanceSince == 0L) state.imbalanceSince = now
            else if (now - state.imbalanceSince >= 72 * HOUR && state.transferUntil < now && (now - state.imbalanceSince) % (24 * HOUR) < 60_000) {
                for (op in s.playerList.players.filter { isOp(it) }) op.sendSystemMessage(Gui.text("국가 간 최근 72시간 활동 국민 수 차이가 72시간 넘게 2명 이상입니다. 이적 창(/snw transfer open <시간>)을 검토하십시오.", Gui.rgb(0xFF7E00)))
                Nations.publish("transfer_suggested", mapOf("counts" to counts))
            }
        } else state.imbalanceSince = 0L
    }

    fun register() {
        ServerLifecycleEvents.SERVER_STARTING.register { s -> load(s) }
        ServerLifecycleEvents.SERVER_STOPPING.register { s -> save(s) }
        ServerPlayConnectionEvents.JOIN.register { h, _, s -> onJoin(s, h.player) }
        ServerPlayConnectionEvents.DISCONNECT.register { h, _ -> state.lastSeen[h.player.uuid.toString()] = System.currentTimeMillis() }
        ServerPlayerEvents.AFTER_RESPAWN.register { _, np, _ -> onRespawn(np) }
        // 시즌 전 캠프: 피해 없음
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { e, _, _ -> !(e is ServerPlayer && !state.started && !(isOp(e) && e.isCreative)) }
        var t = 0
        ServerTickEvents.END_SERVER_TICK.register { s -> if (++t % 1200 == 0) { minute(s); if (t % 12_000 == 0) save(s) } }
        Snw.log.info("[simpleNationWar] 시즌 운영 등록")
    }
}
