package dev.menonng.snw.discord

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.menonng.snw.ui.Gui
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToInt

/**
 * 게임 안 상호평가 정보 창(보기 전용, UI 는 무조건 상자창). **평가는 디스코드에서만 한다**(peer-rating.md).
 * 봇이 보내는 rating.info 에는 사람마다 자기 진행(디스코드에서 몇 명 평가했는지·마감)과, 결과가 공개된 뒤 자기 점수만 들어 있다.
 * `/평가` 로 연다. 다른 사람의 점수나 누가 몇 점을 줬는지는 오지도 않고 보이지도 않는다.
 */
object RatingGui {
    data class Open(val round: Long, val deadline: Long, val done: Int, val total: Int)
    data class Cat(val label: String, val value: Double, val n: Int)
    data class Info(val open: Open?, val resultRound: Long?, val result: List<Cat>)

    private val infos = HashMap<UUID, Info>()
    private val YELLOW = Gui.rgb(0xFFCC11)
    private val fmt = DateTimeFormatter.ofPattern("M월 d일 HH:mm").withZone(ZoneId.of("Asia/Seoul"))

    fun onInfo(d: JsonObject) {
        infos.clear()
        for (e in d.getAsJsonArray("players") ?: JsonArray()) {
            val o = e.asJsonObject
            val uuid = runCatching { UUID.fromString(o["uuid"].asString) }.getOrNull() ?: continue
            val open = o.getAsJsonObject("open")?.let { Open(it["round"].asLong, it["deadline"].asLong, it["done"].asInt, it["total"].asInt) }
            val res = o.getAsJsonObject("result")
            val cats = res?.getAsJsonArray("categories")?.map { c -> c.asJsonObject.let { Cat(it["label"].asString, it["value"].asDouble, it["n"].asInt) } }.orEmpty()
            infos[uuid] = Info(open, res?.get("round")?.asLong, cats)
        }
    }

    fun info(p: ServerPlayer): Info? = infos[p.uuid]

    /** 접속했을 때 한 줄 알림이 필요한가: 평가가 열려 있고 아직 다 하지 않았다. */
    fun needsReminder(p: ServerPlayer): Boolean = info(p)?.open?.let { it.done < it.total && it.deadline > System.currentTimeMillis() } == true

    private fun plain(label: String) = label.replace(Regex("^[^가-힣A-Za-z0-9]+"), "").trim()     // 게임 글꼴에 없는 이모지는 뗀다

    /** 게임 글꼴에는 별 이모지가 없을 수 있어 노란 ★ 로 쓴다(빈 별은 회색 ☆). */
    private fun stars(v: Double): net.minecraft.network.chat.MutableComponent {
        val n = v.roundToInt().coerceIn(1, 5)
        return Gui.text("★".repeat(n), YELLOW).append(Gui.gray("☆".repeat(5 - n))).append(Gui.text("  " + "%.1f".format(v)))
    }

    fun open(p: ServerPlayer, info: Info? = info(p)) {
        val gui = Gui(Gui.text("상호평가 · 내 정보"))
        for (k in 0 until 27) gui.set(k, Gui.filler())
        // 진행 상황
        val o = info?.open
        val status: ItemStack = when {
            o == null -> Gui.item(Items.CLOCK, Gui.text("지금 진행 중인 평가가 없습니다"), listOf(Gui.gray("평가는 디스코드에서만 합니다")))
            o.done >= o.total -> Gui.item(Items.CLOCK, Gui.text("평가를 모두 마쳤습니다", Gui.rgb(0x55BB44)),
                listOf(Gui.gray("디스코드에서 ${o.done} / ${o.total} 명"), Gui.gray("마감 ${fmt.format(Instant.ofEpochMilli(o.deadline))}"), Gui.gray("마감 전까지 디스코드에서 고칠 수 있습니다")), glint = true)
            else -> Gui.item(Items.CLOCK, Gui.text("상호평가 진행 중", YELLOW),
                listOf(Gui.gray("디스코드에서 ${o.done} / ${o.total} 명 평가함"), Gui.gray("마감 ${fmt.format(Instant.ofEpochMilli(o.deadline))}"), Gui.gray("디스코드 DM 의 [평가하기]로 이어서 하세요")))
        }
        gui.set(4, status)
        // 내 결과(공개된 뒤에만, 본인 것만)
        if (info?.result.isNullOrEmpty()) {
            gui.set(13, Gui.item(Items.BOOK, Gui.text("내 결과"), listOf(Gui.gray("결과가 공개되면 여기에서 볼 수 있습니다"))))
        } else {
            gui.set(10, Gui.item(Items.WRITABLE_BOOK, Gui.text("내 결과 (라운드 ${info!!.resultRound})"),
                listOf(Gui.gray("나만 볼 수 있습니다"), Gui.gray("평가가 적게 모인 항목은 전체 평균 쪽으로 보정했습니다"))))
            info.result.take(5).forEachIndexed { i, c ->
                gui.set(12 + i, Gui.item(Items.NETHER_STAR, Gui.text(plain(c.label)), listOf(stars(c.value), Gui.gray("평가 ${c.n}명"))))
            }
        }
        gui.set(22, Gui.item(Items.PAPER, Gui.text("평가는 디스코드에서만 합니다"), listOf(Gui.gray("누가 누구에게 몇 점을 줬는지는 공개되지 않습니다"))))
        gui.open(p)
    }

    /** 운영자 미리보기: 봇 없이 예시 정보로 창을 띄운다. */
    fun preview(p: ServerPlayer) {
        val labels = listOf("⚔️ 전투" to 3.8, "🏗️ 건축" to 3.1, "⛏️ 생존·자원" to 4.2, "🤝 협동·소통" to 4.6, "🔥 열정(접속)" to 2.9)
        open(p, Info(Open(1, System.currentTimeMillis() + 20 * 3_600_000L, 9, 14), 0, labels.map { Cat(it.first, it.second, 11) }))
    }
}
