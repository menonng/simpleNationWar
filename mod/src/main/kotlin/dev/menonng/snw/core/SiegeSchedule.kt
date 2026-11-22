package dev.menonng.snw.core

import com.google.gson.GsonBuilder
import dev.menonng.snw.logic.core.SiegeWindow
import dev.menonng.snw.logic.core.Window
import dev.menonng.snw.nation.NationCommands
import dev.menonng.snw.nation.Nations
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 공성 시간대 운영(nation.md 5-1-2): 1분마다 잠수가 아닌 동시 접속 인원 수만 시간 칸에 모으고(누가 접속했는지는 남기지 않는다),
 * 매일 06:00 에 최근 14일로 평일·주말 창을 다시 고른다. 바뀐 창은 알린 뒤 24시간 뒤부터 적용한다.
 */
object SiegeSchedule {
    val zone: ZoneId = ZoneId.of("Asia/Seoul")

    data class Pending(val startHour: Int, val effectiveAt: Long)
    data class State(
        var weekday: Int = SiegeWindow.DEFAULT.startHour,
        var weekend: Int = SiegeWindow.DEFAULT.startHour,
        var pendingWeekday: Pending? = null,
        var pendingWeekend: Pending? = null,
        /** 날짜(yyyy-MM-dd) → 24칸 [합, 표본 수]. */
        val samples: MutableMap<String, DoubleArray> = linkedMapOf(),
        var lastRecompute: String? = null,
    )

    var state = State()
        private set
    private val gson = GsonBuilder().create()
    private fun file(s: MinecraftServer) = s.getWorldPath(LevelResource.ROOT).resolve("snw").resolve("siege.json")

    fun load(s: MinecraftServer) {
        val f = file(s)
        if (Files.exists(f)) runCatching { state = gson.fromJson(Files.readString(f), State::class.java) }
    }

    fun save(s: MinecraftServer) {
        val f = file(s); Files.createDirectories(f.parent); Files.writeString(f, gson.toJson(state))
    }

    fun windows(now: Long = System.currentTimeMillis()): Pair<Window, Window> {
        state.pendingWeekday?.let { if (now >= it.effectiveAt) { state.weekday = it.startHour; state.pendingWeekday = null } }
        state.pendingWeekend?.let { if (now >= it.effectiveAt) { state.weekend = it.startHour; state.pendingWeekend = null } }
        return Window(state.weekday) to Window(state.weekend)
    }

    /** 운영자 수동 열기/닫기(null = 자동). 저장하지 않는다(재시작하면 자동). */
    @Volatile var forced: Boolean? = null

    fun isOpen(now: Long = System.currentTimeMillis()): Boolean {
        forced?.let { return it }
        val (wd, we) = windows(now)
        return SiegeWindow.isOpen(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone), wd, we)
    }

    fun todayLabel(now: Long = System.currentTimeMillis()): String {
        val (wd, we) = windows(now)
        val d = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone)
        val w = if (SiegeWindow.isWeekend(d.dayOfWeek)) we else wd
        return "%02d:00~%02d:00".format(w.startHour, (w.startHour + w.lengthHours) % 24)
    }

    /** 1분마다 부른다. */
    fun sample(s: MinecraftServer) {
        val now = LocalDateTime.now(zone)
        val active = s.playerList.players.count { !Nations.afkCheck(it.uuid) }
        val arr = state.samples.getOrPut(now.toLocalDate().toString()) { DoubleArray(48) }
        arr[now.hour * 2] += active.toDouble(); arr[now.hour * 2 + 1] += 1.0
        // 오래된 날짜 정리
        val oldest = now.toLocalDate().minusDays(SiegeWindow.SURVEY_DAYS.toLong() + 1)
        state.samples.keys.removeIf { runCatching { LocalDate.parse(it).isBefore(oldest) }.getOrDefault(true) }
        if (now.hour == 6 && state.lastRecompute != now.toLocalDate().toString()) {
            state.lastRecompute = now.toLocalDate().toString()
            recompute(s, now.toLocalDate())
        }
    }

    private fun curve(today: LocalDate, weekend: Boolean): Pair<DoubleArray, Int> {
        val hourly = DoubleArray(24); var days = 0
        for ((k, arr) in state.samples) {
            val d = runCatching { LocalDate.parse(k) }.getOrNull() ?: continue
            if (!d.isBefore(today) || SiegeWindow.isWeekend(d.dayOfWeek) != weekend) continue
            days++
            for (h in 0 until 24) if (arr[h * 2 + 1] > 0) hourly[h] += arr[h * 2] / arr[h * 2 + 1]
        }
        if (days > 0) for (h in 0 until 24) hourly[h] /= days
        return hourly to days
    }

    fun recompute(s: MinecraftServer, today: LocalDate) {
        val now = System.currentTimeMillis()
        val (wdCurve, wdDays) = curve(today, false)
        val (weCurve, weDays) = curve(today, true)
        val (wd, we) = windows(now)
        val nwd = SiegeWindow.next(wdCurve, wdDays, SiegeWindow.MIN_WEEKDAY_SAMPLES, wd)
        val nwe = SiegeWindow.next(weCurve, weDays, SiegeWindow.MIN_WEEKEND_SAMPLES, we)
        val notice = now + SiegeWindow.NOTICE_HOURS * 3_600_000L
        if (nwd != wd) { state.pendingWeekday = Pending(nwd.startHour, notice); announce(s, "평일", nwd) }
        if (nwe != we) { state.pendingWeekend = Pending(nwe.startHour, notice); announce(s, "주말", nwe) }
        save(s)
    }

    private fun announce(s: MinecraftServer, kind: String, w: Window) {
        val label = "%02d:00~%02d:00".format(w.startHour, (w.startHour + w.lengthHours) % 24)
        NationCommands.broadcast(s, "$kind 공성 시간이 24시간 뒤부터 $label 로 바뀝니다. 서버 접속이 가장 많은 시간에 맞췄습니다.")
        Nations.publish("siege_window_changed", mapOf("kind" to kind, "window" to label))
    }
}
