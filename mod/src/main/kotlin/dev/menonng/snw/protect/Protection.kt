package dev.menonng.snw.protect

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dev.menonng.snw.api.ChunkKey
import dev.menonng.snw.api.KillRecord
import dev.menonng.snw.logic.afk.Activity
import dev.menonng.snw.logic.afk.AfkTracker
import dev.menonng.snw.logic.combat.CombatTag
import dev.menonng.snw.logic.combat.QuitReason
import dev.menonng.snw.logic.combat.QuitVerdict
import dev.menonng.snw.logic.kda.Allegiance
import dev.menonng.snw.logic.kda.DamageEntry
import dev.menonng.snw.logic.kda.DeathInput
import dev.menonng.snw.logic.kda.KdaJudge
import dev.menonng.snw.logic.kda.KdaStats
import dev.menonng.snw.nation.NationCommands
import dev.menonng.snw.nation.Nations
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.util.EnumSet
import java.util.UUID

/**
 * 보호(nation.md 6절)·같은 국가 사망(2-1)·전투 중 로그아웃(16절)·잠수(15절)·K/D/A('KDA').
 * - 약탈은 전면 허용. 막는 것은 생존자 캠프(건축·PvP)와 블러드문 휴전 동안의 남의 영토뿐.
 * - 동맹은 어디서든 서로 피해 없음. 같은 국가에게 죽으면 그 자리 부활 + 인벤세이브, K/D 에 넣지 않음.
 * - 접속·부활 직후 보호 중인 플레이어의 공격은 0(16절, 2026-11-22).
 */
object Protection {
    const val CAMP_RADIUS = 64.0
    const val SPAWN_PROTECT_MILLIS = 3_000L

    val afk = AfkTracker()
    val combat = CombatTag()
    private val judge = KdaJudge(object : Allegiance {
        override fun sameNation(a: UUID, b: UUID) = Nations.registry.sameNation(a, b)
        override fun allied(a: UUID, b: UUID) = Nations.registry.allied(a, b)
    })
    private val damage = HashMap<UUID, ArrayDeque<DamageEntry>>()
    private val protectedUntil = HashMap<UUID, Long>()
    private val wasAfk = HashSet<UUID>()
    private val lastLook = HashMap<UUID, DoubleArray>()
    var stopping = false
        private set

    // ---- K/D/A 저장 ----
    data class Record(var stats: KdaStats = KdaStats(0, 0, 0, 0), val log: MutableList<Map<String, Any?>> = mutableListOf())
    private val records = HashMap<String, Record>()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var recordsDirty = false
    fun stats(id: UUID): KdaStats = records[id.toString()]?.stats ?: KdaStats(0, 0, 0, 0)

    private fun file(s: MinecraftServer) = s.getWorldPath(LevelResource.ROOT).resolve("snw").resolve("players.json")

    // ---- 판정 도우미 ----
    fun dim(level: Level): String = level.dimension().identifier().toString()
    fun chunkOf(level: Level, pos: BlockPos) = ChunkKey(dim(level), pos.x shr 4, pos.z shr 4)

    fun inCamp(level: Level, x: Double, z: Double): Boolean = dim(level) == "minecraft:overworld" && x * x + z * z <= CAMP_RADIUS * CAMP_RADIUS

    fun inDefenseZone(level: Level, x: Double, z: Double): Boolean {
        if (!Nations.truceActive) return false
        val d = dim(level); val r = Nations.truceRadius
        return Nations.truceZones.any { it.dimension == d && (it.x - x) * (it.x - x) + (it.z - z) * (it.z - z) <= r * r }
    }

    fun isProtected(p: UUID, now: Long = System.currentTimeMillis()): Boolean = (protectedUntil[p] ?: 0L) > now
    fun markProtected(p: UUID, until: Long) { protectedUntil[p] = maxOf(protectedUntil[p] ?: 0L, until) }

    /** 블러드문 휴전 중 [player] 가 [pos] 의 블록을 바꾸거나 열면 약탈인지: 자국·동맹이 아닌 국가의 영토. */
    private fun raidBlocked(player: ServerPlayer, level: Level, pos: BlockPos): Boolean {
        if (!Nations.truceActive) return false
        val owner = Nations.registry.ownerOf(chunkOf(level, pos)) ?: return false
        val mine = Nations.registry.nationOf(player.uuid)?.id
        return owner.id != mine && (mine == null || Nations.registry.allyOf(mine) != owner.id)
    }

    private fun buildBlocked(player: ServerPlayer, level: Level, pos: BlockPos): String? = when {
        player.isCreative && net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(player.permissions()) -> null
        inCamp(level, pos.x + 0.5, pos.z + 0.5) -> "생존자 캠프에서는 블록을 바꿀 수 없습니다."
        raidBlocked(player, level, pos) -> "블러드문 휴전 중에는 다른 국가 영토를 약탈할 수 없습니다."
        else -> null
    }

    /** 플레이어끼리 피해를 막을지. 이유 문자열(보낼 말) 또는 null(허용). */
    fun pvpBlocked(attacker: ServerPlayer, victim: ServerPlayer): String? {
        val now = System.currentTimeMillis()
        val level = victim.level()
        return when {
            attacker === victim -> null
            isProtected(attacker.uuid, now) -> "접속·부활 직후 보호 중에는 공격할 수 없습니다."
            Nations.registry.allied(attacker.uuid, victim.uuid, now) -> "동맹 국민은 공격할 수 없습니다."
            inCamp(level, victim.x, victim.z) || inCamp(attacker.level(), attacker.x, attacker.z) -> "생존자 캠프에서는 싸울 수 없습니다."
            Nations.truceActive && (Nations.registry.ownerOf(chunkOf(level, victim.blockPosition())) != null || inDefenseZone(level, victim.x, victim.z)) ->
                "블러드문 휴전 중에는 영토와 방어 구역에서 싸울 수 없습니다."
            else -> null
        }
    }

    fun attackerPlayer(source: DamageSource): ServerPlayer? = source.entity as? ServerPlayer

    private fun tell(p: ServerPlayer, msg: String) { p.sendSystemMessage(Component.literal(msg).withStyle(ChatFormatting.GRAY), true) }

    fun register() {
        Nations.afkCheck = { afk.isAfk(it, System.currentTimeMillis()) }

        ServerLifecycleEvents.SERVER_STARTED.register { s ->
            stopping = false
            val f = file(s)
            if (Files.exists(f)) runCatching {
                val type = object : TypeToken<HashMap<String, Record>>() {}.type
                records.putAll(gson.fromJson<HashMap<String, Record>>(Files.readString(f), type))
            }
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { s -> stopping = true; saveRecords(s) }

        // ---- 블록: 캠프·휴전 약탈 ----
        PlayerBlockBreakEvents.BEFORE.register { level, player, pos, _, _ ->
            val p = player as? ServerPlayer ?: return@register true
            afk.onActivity(p.uuid, Activity.BLOCK, System.currentTimeMillis())
            buildBlocked(p, level, pos)?.let { tell(p, it); false } ?: true
        }
        UseBlockCallback.EVENT.register { player, level, _, hit ->
            val p = player as? ServerPlayer ?: return@register InteractionResult.PASS
            afk.onActivity(p.uuid, Activity.BLOCK, System.currentTimeMillis())
            val target = hit.blockPos
            val placeAt = target.relative(hit.direction)
            val reason = buildBlocked(p, level, target) ?: buildBlocked(p, level, placeAt)
            if (reason != null) { tell(p, reason); InteractionResult.FAIL } else InteractionResult.PASS
        }
        UseItemCallback.EVENT.register { player, _, _ ->
            (player as? ServerPlayer)?.let { afk.onActivity(it.uuid, Activity.INTERACT, System.currentTimeMillis()) }
            InteractionResult.PASS
        }
        AttackEntityCallback.EVENT.register { player, _, _, _, _ ->
            (player as? ServerPlayer)?.let { afk.onActivity(it.uuid, Activity.ATTACK, System.currentTimeMillis()) }
            InteractionResult.PASS
        }
        ServerMessageEvents.CHAT_MESSAGE.register { _, sender, _ -> afk.onActivity(sender.uuid, Activity.CHAT, System.currentTimeMillis()) }
        ServerMessageEvents.COMMAND_MESSAGE.register { _, src, _ -> src.player?.let { afk.onActivity(it.uuid, Activity.COMMAND, System.currentTimeMillis()) } }

        // ---- PvP 피해 ----
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { victim, source, _ ->
            val v = victim as? ServerPlayer ?: return@register true
            val a = attackerPlayer(source) ?: return@register true
            val reason = pvpBlocked(a, v)
            if (reason != null) { if (source.directEntity === a) tell(a, reason); false } else true
        }
        ServerLivingEntityEvents.AFTER_DAMAGE.register { victim, source, _, taken, _ ->
            val v = victim as? ServerPlayer ?: return@register
            val now = System.currentTimeMillis()
            val a = attackerPlayer(source)
            if (a != null && a !== v) {
                damage.getOrPut(v.uuid) { ArrayDeque() }.apply { addLast(DamageEntry(a.uuid, taken.toDouble(), now)); while (size > 32) removeFirst() }
                if (!Nations.registry.sameNation(a.uuid, v.uuid)) combat.onPvpHit(a.uuid, v.uuid, now)
            } else if (source.entity is Enemy) combat.onZombieHit(v.uuid, now)
        }

        // ---- 같은 국가 사망: 그 자리 부활 + 인벤세이브 ----
        ServerPlayerEvents.ALLOW_DEATH.register { player, source, _ ->
            val killer = attackerPlayer(source) ?: return@register true
            if (killer === player || !Nations.registry.sameNation(killer.uuid, player.uuid)) return@register true
            player.setHealth(player.maxHealth)
            player.clearFire()
            player.removeAllEffects()
            player.fallDistance = 0.0
            damage.remove(player.uuid)
            val r = records.getOrPut(killer.uuid.toString()) { Record() }
            r.stats = r.stats.copy(teamKills = r.stats.teamKills + 1); recordsDirty = true
            player.sendSystemMessage(Component.literal("같은 국가 국민에게 쓰러져 그 자리에서 다시 일어났습니다. 소지품은 그대로입니다.").withStyle(ChatFormatting.GRAY))
            false
        }
        ServerLivingEntityEvents.AFTER_DEATH.register { entity, source ->
            val v = entity as? ServerPlayer ?: return@register
            onPlayerDeath(v, source)
        }
        ServerPlayerEvents.AFTER_RESPAWN.register { _, newPlayer, _ -> markProtected(newPlayer.uuid, System.currentTimeMillis() + SPAWN_PROTECT_MILLIS) }

        // ---- 접속·전투 중 로그아웃 ----
        ServerPlayConnectionEvents.JOIN.register { h, _, _ ->
            val now = System.currentTimeMillis()
            afk.onJoin(h.player.uuid, now)
            markProtected(h.player.uuid, now + SPAWN_PROTECT_MILLIS)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { h, s ->
            val p = h.player
            val now = System.currentTimeMillis()
            val reason = if (stopping || s.isStopped) QuitReason.SERVER_STOP else QuitReason.QUIT
            when (val v = combat.onDisconnect(p.uuid, reason, now)) {
                is QuitVerdict.PvpDeath -> {
                    val killer = s.playerList.getPlayer(v.killer)
                    val src = if (killer != null) p.level().damageSources().playerAttack(killer) else p.level().damageSources().genericKill()
                    p.hurtServer(p.level(), src, Float.MAX_VALUE)
                    NationCommands.broadcast(s, "${p.scoreboardName}이(가) 전투 중에 접속을 끊어 쓰러진 것으로 처리되었습니다.", 0xAAAAAA)
                }
                QuitVerdict.None -> {}
            }
            wasAfk.remove(p.uuid); lastLook.remove(p.uuid)
        }

        // ---- 1초마다: 잠수 판정·탭 표시·캠프 몹 ----
        var t = 0
        ServerTickEvents.END_SERVER_TICK.register { s ->
            if (++t % 20 != 0) return@register
            val now = System.currentTimeMillis()
            val changed = mutableListOf<ServerPlayer>()
            for (p in s.playerList.players) {
                val last = lastLook[p.uuid]
                val cur = doubleArrayOf(p.yRot.toDouble(), p.xRot.toDouble(), p.x, p.z)
                if (last != null) {
                    val looked = Math.abs(cur[0] - last[0]) >= 1.0 || Math.abs(cur[1] - last[1]) >= 1.0
                    val walked = p.vehicle == null && !p.isInWater && (Math.abs(cur[2] - last[2]) + Math.abs(cur[3] - last[3])) > 0.2
                    if (looked) afk.onActivity(p.uuid, Activity.LOOK, now) else if (walked) afk.onActivity(p.uuid, Activity.MOVE_INPUT, now)
                }
                lastLook[p.uuid] = cur
                afk.tick(p.uuid, now)
                val isAfk = afk.isAfk(p.uuid, now)
                if (isAfk != (p.uuid in wasAfk)) { if (isAfk) wasAfk += p.uuid else wasAfk -= p.uuid; changed += p }
                val left = combat.remaining(p.uuid, now)
                if (left > 0) p.sendSystemMessage(Component.literal("전투 중 ${(left + 999) / 1000}초 — 지금 접속을 끊으면 쓰러진 것으로 처리됩니다.").withStyle(ChatFormatting.RED), true)
            }
            if (changed.isNotEmpty()) s.playerList.broadcastAll(ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME), changed))
            if (t % 200 == 0 && recordsDirty) saveRecords(s)
        }
        // 생존자 캠프: 적대 몹이 생기지 않는다(블러드문 좀비도 캠프에는 오지 않는다, 11절)
        ServerEntityEvents.ENTITY_LOAD.register { e, level ->
            if (e is Enemy && e !is ServerPlayer && inCamp(level, e.x, e.z) && !e.entityTags().contains("snw_camp_allowed")) e.discard()
        }
    }

    /** 잠수 중이면 탭 목록 이름 뒤에 회색 [잠수](믹스인이 부른다). */
    @JvmStatic
    fun tabName(p: ServerPlayer, base: Component?): Component? {
        if (p.uuid !in wasAfk) return base
        val name = base?.copy() ?: (p.team?.getFormattedName(p.name) ?: p.name.copy())
        return name.copy().append(Component.literal(" [잠수]").withStyle(ChatFormatting.GRAY))
    }

    private fun onPlayerDeath(v: ServerPlayer, source: DamageSource) {
        val now = System.currentTimeMillis()
        val killer = attackerPlayer(source) ?: (v.killCredit as? ServerPlayer)
        val entries = damage.remove(v.uuid)?.toList() ?: emptyList()
        val out = judge.judge(DeathInput(v.uuid, killer?.uuid, v.maxHealth.toDouble(), entries, now))
        combat.onDeath(v.uuid)
        val vr = records.getOrPut(v.uuid.toString()) { Record() }
        vr.stats = vr.stats.apply(out, KdaStats.Role.VICTIM)
        if (killer != null && killer !== v) {
            val kr = records.getOrPut(killer.uuid.toString()) { Record() }
            kr.stats = kr.stats.apply(out, KdaStats.Role.KILLER)
            if (out.killCounted) kr.log += mapOf("t" to now / 1000, "type" to "kill", "victim" to v.uuid.toString(), "assists" to out.assists.map { it.toString() },
                "where" to dim(v.level()), "x" to v.blockPosition().x, "z" to v.blockPosition().z)
        }
        for (a in out.assists) records.getOrPut(a.toString()) { Record() }.let { it.stats = it.stats.apply(out, KdaStats.Role.ASSIST) }
        recordsDirty = true
        Nations.notifyKill(KillRecord(v.uuid, killer?.uuid, out.assists, out.pvpDeath, out.killCounted, now))
    }

    private fun saveRecords(s: MinecraftServer) {
        val f = file(s); Files.createDirectories(f.parent)
        Files.writeString(f, gson.toJson(records)); recordsDirty = false
    }

    @JvmStatic
    fun territoryProtectedFromGriefing(level: Level, pos: BlockPos): Boolean =
        level is ServerLevel && Nations.registry.ownerOf(chunkOf(level, pos)) != null

    @Suppress("unused")
    private fun entityChunk(e: Entity) = chunkOf(e.level(), e.blockPosition())
}
