package dev.menonng.snw.project

import com.google.gson.GsonBuilder
import dev.menonng.snw.logic.state.NationRegistry
import dev.menonng.snw.nation.NationCommands
import dev.menonng.snw.nation.NationGui
import dev.menonng.snw.nation.Nations
import dev.menonng.snw.nation.Treasury
import dev.menonng.snw.nickname.Nicknames
import dev.menonng.snw.ui.Gui
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Items
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 백신 조각 현황(nation.md 9-2, 치료제 엔딩 분기). zomApo 가 조각 확보를 `vaccine_shard` 사건으로 알리면 국가별로 센다.
 * 연구 탭: 서버 전체 조각 수, 국가별 수(국가 색 막대), 주운 사람(동의한 사람만, 닉네임), 마지막 밤까지 남은 날.
 * 엔딩이 어떻게 갈리는지는 일부러 숫자로 보여 주지 않는다.
 */
object VaccineBoard {
    data class Find(val player: String, val nation: String?, val t: Long)
    data class State(val finds: MutableList<Find> = mutableListOf(), val consent: MutableSet<String> = linkedSetOf())
    var state = State()
        private set
    private val gson = GsonBuilder().create()
    private fun file(s: MinecraftServer) = s.getWorldPath(LevelResource.ROOT).resolve("snw").resolve("vaccine.json")
    /** 시즌 마지막 날(최후의 밤). */
    val seasonEnd: LocalDate = LocalDate.of(2027, 2, 7)

    fun total(): Int = state.finds.size
    fun byNation(n: String): Int = state.finds.count { it.nation == n }

    fun onShard(s: MinecraftServer, player: UUID) {
        val nation = Nations.registry.nationOf(player)?.id
        state.finds += Find(player.toString(), nation, System.currentTimeMillis())
        save(s)
        val n = nation?.let { Nations.registry.nation(it) }
        NationCommands.broadcast(s, "${n?.name ?: "누군가"}이(가) 백신 조각을 확보했습니다. 서버 전체 ${total()}개.", 0x39C5BB)
        if (nation != null) Treasury.discover(s, nation, "vaccine")
    }

    fun toggleConsent(p: ServerPlayer): String {
        val id = p.uuid.toString()
        return if (state.consent.remove(id)) { save(p.level().server); "연구 탭에 이름을 보이지 않습니다." }
        else { state.consent += id; save(p.level().server); "연구 탭 '조각을 주운 사람'에 닉네임이 보입니다." }
    }

    private fun bar(rgb: Int) = when (rgb) {
        0xFF0045 -> Items.STAINED_GLASS_PANE.red(); 0x3355BB -> Items.STAINED_GLASS_PANE.blue(); 0xFFCC11 -> Items.STAINED_GLASS_PANE.yellow()
        else -> Items.STAINED_GLASS_PANE.white()
    }

    private fun tab(gui: Gui, p: ServerPlayer, n: NationRegistry.Nation) {
        val s = p.level().server
        val left = ChronoUnit.DAYS.between(LocalDate.now(ZoneId.of("Asia/Seoul")), seasonEnd).coerceAtLeast(0)
        gui.set(19, Gui.item(Items.AMETHYST_CLUSTER, Gui.text("서버 전체 백신 조각 ${total()}개", Gui.rgb(0x39C5BB)), listOf(Gui.gray("마지막 밤까지 ${left}일"))))
        Nations.registry.alive().take(3).forEachIndexed { row, o ->
            val c = byNation(o.id)
            gui.set(21 + row * 9, Gui.item(Gui.banner(o.colorRgb), Gui.text("${o.name} ${c}개", Gui.rgb(o.colorRgb)), hide = setOf(net.minecraft.core.component.DataComponents.BANNER_PATTERNS)))
            for (i in 0 until minOf(c, 6)) gui.set(22 + row * 9 + i, Gui.item(bar(o.colorRgb), Gui.text("${o.name} ${c}개", Gui.rgb(o.colorRgb))))
        }
        val names = state.finds.filter { it.player in state.consent }.map { f -> runCatching { UUID.fromString(f.player) }.getOrNull()?.let { Nicknames.get(it) ?: Nations.nameOf(s, it) } ?: "?" }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(10).map { Gui.gray("${it.key} ${it.value}개") }
        gui.set(46, Gui.item(Items.WRITABLE_BOOK, Gui.text("조각을 주운 사람"), names.ifEmpty { listOf(Gui.gray("이름을 보이기로 한 사람이 아직 없습니다")) } +
            Gui.gray(if (p.uuid.toString() in state.consent) "눌러서 내 이름 숨기기" else "눌러서 내 이름 보이기"))) { pl -> pl.sendSystemMessage(Gui.text(toggleConsent(pl)), true); NationGui.open(pl, NationGui.Tab.RESEARCH) }
    }

    fun load(s: MinecraftServer) { val f = file(s); if (Files.exists(f)) runCatching { state = gson.fromJson(Files.readString(f), State::class.java) } }
    fun save(s: MinecraftServer) { val f = file(s); Files.createDirectories(f.parent); Files.writeString(f, gson.toJson(state)) }

    fun register() {
        ServerLifecycleEvents.SERVER_STARTING.register { s -> load(s) }
        NationGui.researchTab = { g, p, n -> tab(g, p, n) }
        Nations.eventBus().subscribe("vaccine_shard") { e ->
            val s = Nations.server ?: return@subscribe
            (e.data["player"] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { s.execute { onShard(s, it) } }
        }
    }
}
