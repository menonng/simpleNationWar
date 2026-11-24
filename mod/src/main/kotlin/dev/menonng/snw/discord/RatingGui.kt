package dev.menonng.snw.discord

import com.google.gson.JsonObject
import dev.menonng.snw.logic.discord.Protocol
import dev.menonng.snw.ui.Gui
import net.minecraft.core.component.DataComponents
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.UUID

/**
 * 게임 안 상호평가 창(docs/discord/peer-rating.md 2절, UI 는 무조건 상자창). 봇의 rating.request 를 받아 두고,
 * 대상 플레이어가 `/평가` 로 연다. 6줄: 맨 윗줄 = 대상(이전·다음·제출), 아래 5줄 = 항목마다 ⭐1~5·잘 모름.
 * 제출하면 rating.submit 으로 봇에 보내고(같은 DB), 다음 대상으로 넘어간다.
 */
object RatingGui {
    data class Target(val uuid: String, val nickname: String)
    data class Request(val round: Long, val deadline: Long, val categories: List<Pair<String, String>>, val targets: Map<String, List<Target>>)

    @Volatile var request: Request? = null; private set
    private val cursor = HashMap<UUID, Int>()
    private val picks = HashMap<Pair<UUID, String>, MutableMap<String, Int?>>()      // (평가자, 대상) → 항목 → 별(0 = 잘 모름)
    private val done = HashMap<UUID, MutableSet<String>>()

    fun onRequest(d: JsonObject) {
        val cats = d.getAsJsonArray("categories")?.map { it.asJsonObject["id"].asString to it.asJsonObject["label"].asString }.orEmpty()
        val targets = d.getAsJsonArray("pairs")?.associate { e ->
            val o = e.asJsonObject
            o["rater"].asString to o.getAsJsonArray("targets").map { t -> Target(t.asJsonObject["uuid"].asString, t.asJsonObject["nickname"]?.asString ?: "?") }
        }.orEmpty()
        request = Request(d["round"].asLong, d["deadline"].asLong, cats, targets)
        cursor.clear(); picks.clear(); done.clear()
    }

    fun available(p: ServerPlayer): Boolean = request?.let { it.deadline > System.currentTimeMillis() && it.targets.containsKey(p.uuid.toString()) } == true

    // 게임 글꼴에는 별 이모지가 없을 수 있어 노란 ★ 로 쓴다(고르지 않은 칸은 회색 ☆)
    private val YELLOW = Gui.rgb(0xFFCC11)
    private fun stars(n: Int): net.minecraft.network.chat.MutableComponent =
        Gui.text("★".repeat(n), YELLOW).append(Gui.gray("☆".repeat(5 - n)))
    private fun star(n: Int, on: Boolean): ItemStack =
        Gui.item(if (on) Items.STAINED_GLASS_PANE.yellow() else Items.STAINED_GLASS_PANE.white(), stars(n), listOf(Gui.gray("$n 점")), glint = on)

    /** 운영자 미리보기: 봇 없이 예시 대상 3명으로 창을 띄운다(제출해도 봇이 없으면 보내지지 않는다). */
    fun preview(p: ServerPlayer) {
        val o = JsonObject()
        o.addProperty("round", 0); o.addProperty("deadline", System.currentTimeMillis() + 3_600_000L)
        o.add("categories", com.google.gson.JsonArray().apply {
            dev.menonng.snw.logic.discord.PeerRating.DEFAULT_CATEGORIES.forEach { c -> add(JsonObject().apply { addProperty("id", c.id); addProperty("label", c.label) }) } })
        o.add("pairs", com.google.gson.JsonArray().apply { add(JsonObject().apply {
            addProperty("rater", p.uuid.toString())
            add("targets", com.google.gson.JsonArray().apply { listOf("가람", "나래", "다온").forEach { n -> add(JsonObject().apply { addProperty("uuid", UUID.nameUUIDFromBytes(n.toByteArray()).toString()); addProperty("nickname", n) }) } })
        }) })
        onRequest(o)
        open(p)
    }

    fun open(p: ServerPlayer) {
        val req = request ?: run { p.sendSystemMessage(Gui.text("지금 열린 상호평가가 없습니다."), true); return }
        val list = req.targets[p.uuid.toString()].orEmpty()
        if (list.isEmpty()) { p.sendSystemMessage(Gui.text("평가할 사람이 없습니다."), true); return }
        val i = (cursor[p.uuid] ?: list.indexOfFirst { it.uuid !in done[p.uuid].orEmpty() }.coerceAtLeast(0)).coerceIn(0, list.size - 1)
        cursor[p.uuid] = i
        val t = list[i]
        val sel = picks.getOrPut(p.uuid to t.uuid) { linkedMapOf() }
        val gui = Gui(Gui.text("상호평가 · ${t.nickname} (${i + 1}/${list.size})"))
        for (k in 0 until 54) gui.set(k, Gui.filler())          // 빈 칸은 검은 유리: 별·버튼이 눈에 띄게
        val doneN = done[p.uuid].orEmpty().size
        gui.set(0, Gui.item(Items.ARROW, Gui.text("이전 사람"))) { pl -> cursor[pl.uuid] = (i - 1 + list.size) % list.size; open(pl) }
        gui.set(4, Gui.item(Items.PLAYER_HEAD, Gui.text(t.nickname, Gui.rgb(0x39C5BB)), listOf(Gui.gray("평가한 사람 $doneN / ${list.size}"), Gui.gray("누가 몇 점을 줬는지는 공개되지 않습니다"))))
        val complete = req.categories.all { sel.containsKey(it.first) }
        gui.set(6, if (complete) Gui.item(Items.EMERALD, Gui.text("제출", Gui.rgb(0x55BB44)), listOf(Gui.gray("누르면 이 사람 평가를 보냅니다")))
                   else Gui.item(Items.STAINED_GLASS_PANE.gray(), Gui.gray("제출"), listOf(Gui.gray("모든 항목을 고르면 제출할 수 있습니다")))) { pl ->
            if (!req.categories.all { sel.containsKey(it.first) }) return@set
            DiscordLink.send(Protocol.RATING_SUBMIT, mapOf("round" to req.round, "rater" to pl.uuid.toString(), "target" to t.uuid,
                "scores" to req.categories.associate { (c, _) -> c to sel[c]?.takeIf { it in 1..5 } }))
            done.getOrPut(pl.uuid) { HashSet() } += t.uuid
            pl.sendSystemMessage(Gui.text("${t.nickname} 님 평가를 보냈습니다.", Gui.rgb(0x55BB44)), true)
            val next = list.indexOfFirst { it.uuid !in done[pl.uuid].orEmpty() }
            if (next < 0) { pl.closeContainer(); pl.sendSystemMessage(Gui.text("모두 평가했습니다. 고마워요!", Gui.rgb(0x55BB44))); return@set }
            cursor[pl.uuid] = next; open(pl)
        }
        gui.set(8, Gui.item(Items.ARROW, Gui.text("다음 사람"))) { pl -> cursor[pl.uuid] = (i + 1) % list.size; open(pl) }
        req.categories.take(5).forEachIndexed { row, (c, label) ->
            val base = (row + 1) * 9
            gui.set(base, Gui.item(Items.PAPER, Gui.text(label.replace(Regex("^[^가-힣A-Za-z0-9]+"), "").trim())))   // 게임 글꼴에 없는 이모지는 뗀다
            val cur = sel[c]
            for (s in 1..5) gui.set(base + 1 + s, star(s, cur != null && cur in 1..5 && s <= cur)) { pl -> sel[c] = s; open(pl) }
            gui.set(base + 8, Gui.item(Items.BOOK, Gui.text("잘 모름", if (cur == 0) Gui.rgb(0x39C5BB) else null), listOf(Gui.gray("잘 모르는 사람은 억지로 평가하지 않아도 됩니다")), glint = cur == 0)) { pl -> sel[c] = 0; open(pl) }
        }
        gui.open(p)
    }

    @Suppress("unused") private val keepImport = DataComponents.ITEM_NAME
}
