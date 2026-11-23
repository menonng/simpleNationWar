package dev.menonng.snw.project

import com.google.gson.GsonBuilder
import dev.menonng.snw.SnwConfig
import dev.menonng.snw.api.ChunkKey
import dev.menonng.snw.nation.Nations
import dev.menonng.snw.ui.Gui
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * 주민 상한(nation.md 12절): 국가 영토 안에서 일하는(거래·번식하는) 정주민은 국가당 20명(설정 villager.cap),
 * 그 뒤로 들어온 주민은 '피난민'(이름표, 거래·번식 불가). 번식은 정주민이 상한 미만이고 그 국가 오늘 번식이 4마리 미만일 때만(villager.breed_per_day).
 * 들어온 순서는 영토 안에서 처음 본 시각. 10초마다 다시 센다. '오늘'은 현실 06:00 기준.
 */
object Villagers {
    private const val REFUGEE_TAG = "snw_refugee"
    data class State(
        /** 국가 → 주민 UUID → 들어온 시각. */
        val entered: MutableMap<String, MutableMap<String, Long>> = linkedMapOf(),
        val breedsToday: MutableMap<String, Int> = linkedMapOf(),
        var day: String = "",
    )
    var state = State()
        private set
    private val gson = GsonBuilder().create()
    private val settled = HashMap<String, Set<String>>()       // 국가 → 정주민
    private fun file(s: MinecraftServer) = s.getWorldPath(LevelResource.ROOT).resolve("snw").resolve("villagers.json")

    private fun today(): String { val t = LocalDateTime.now(ZoneId.of("Asia/Seoul")); return (if (t.hour < 6) t.toLocalDate().minusDays(1) else t.toLocalDate()).toString() }

    fun ownerOf(v: Villager): String? = Nations.registry.ownerOf(ChunkKey(v.level().dimension().identifier().toString(), v.blockPosition().x shr 4, v.blockPosition().z shr 4))?.id

    fun isRefugee(v: Villager): Boolean = v.entityTags().contains(REFUGEE_TAG)

    fun summary(nation: String): String = "주민 ${settled[nation]?.size ?: 0}/${SnwConfig.villagerCap} · 오늘 번식 ${state.breedsToday[nation] ?: 0}/${SnwConfig.villagerBreedPerDay}"

    internal fun recount(s: MinecraftServer) = recountWith(s.allLevels.flatMap { l -> l.allEntities.filterIsInstance<Villager>() })

    /** 불러온 주민 목록으로 다시 센다(시험에서 목록을 직접 준다). */
    internal fun recountWith(villagers: List<Villager>) {
        if (state.day != today()) { state.day = today(); state.breedsToday.clear() }
        val now = System.currentTimeMillis()
        val seen = HashMap<String, MutableSet<String>>()
        val byId = HashMap<String, Villager>()
        val loaded = HashSet<String>()
        for (v in villagers) {
            if (!v.isAlive) continue
            loaded += v.uuid.toString()
            val owner = ownerOf(v) ?: run { if (isRefugee(v)) setRefugee(v, false); null } ?: continue
            val id = v.uuid.toString()
            byId[id] = v
            seen.getOrPut(owner) { HashSet() } += id
            val map = state.entered.getOrPut(owner) { linkedMapOf() }
            map.putIfAbsent(id, now)
            for ((other, m) in state.entered) if (other != owner) m.remove(id)          // 다른 나라로 넘어감
        }
        for ((nation, map) in state.entered) {
            val here = seen[nation].orEmpty()
            // 불러온 청크에서 영토를 벗어났거나 죽은 주민은 지운다(언로드된 청크의 주민은 그대로 둔다)
            map.keys.removeIf { id -> id !in here && id in loaded }
            val order = map.entries.sortedBy { it.value }.map { it.key }
            val ok = order.take(SnwConfig.villagerCap).toSet()
            settled[nation] = ok
            for (id in here) byId[id]?.let { setRefugee(it, id !in ok) }
        }
    }

    private fun setRefugee(v: Villager, refugee: Boolean) {
        if (refugee == isRefugee(v)) return
        if (refugee) {
            v.addTag(REFUGEE_TAG)
            if (v.customName == null) { v.customName = Component.literal("피난민"); v.isCustomNameVisible = true }
        } else {
            v.removeTag(REFUGEE_TAG)
            if (v.customName?.string == "피난민") { v.customName = null; v.isCustomNameVisible = false }
        }
    }

    /** 번식 판정(믹스인): 영토 밖이면 바닐라, 영토 안이면 정주민 상한·오늘 번식 수. 아기가 생기면 센다. */
    @JvmStatic
    fun allowBreed(parent: Villager): Boolean {
        val owner = ownerOf(parent) ?: return true
        if (isRefugee(parent)) return false
        if ((settled[owner]?.size ?: 0) >= SnwConfig.villagerCap) return false
        if ((state.breedsToday[owner] ?: 0) >= SnwConfig.villagerBreedPerDay) return false
        return true
    }

    @JvmStatic
    fun onBorn(parent: Villager, baby: Villager) {
        val owner = ownerOf(parent) ?: return
        state.breedsToday.merge(owner, 1, Int::plus)
        state.entered.getOrPut(owner) { linkedMapOf() }[baby.uuid.toString()] = System.currentTimeMillis()
    }

    fun load(s: MinecraftServer) { val f = file(s); if (Files.exists(f)) runCatching { state = gson.fromJson(Files.readString(f), State::class.java) } }
    fun save(s: MinecraftServer) { val f = file(s); Files.createDirectories(f.parent); Files.writeString(f, gson.toJson(state)) }

    fun register() {
        ServerLifecycleEvents.SERVER_STARTING.register { s -> load(s) }
        ServerLifecycleEvents.SERVER_STOPPING.register { s -> save(s) }
        var t = 0
        ServerTickEvents.END_SERVER_TICK.register { s -> if (++t % 200 == 0) recount(s); if (t % 6000 == 0) save(s) }
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register { e, _ ->
            if (e is Villager) state.entered.values.forEach { it.remove(e.uuid.toString()) }      // 죽으면 빈자리
        }
        UseEntityCallback.EVENT.register { player, level, _, entity, _ ->
            if (level !is ServerLevel || entity !is Villager || !isRefugee(entity)) return@register InteractionResult.PASS
            (player as? ServerPlayer)?.sendSystemMessage(Gui.text("피난민은 거래하지 않습니다. 이 국가의 정주민이 이미 ${SnwConfig.villagerCap}명입니다."), true)
            InteractionResult.FAIL
        }
    }
}
