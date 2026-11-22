package dev.menonng.snw.logic.core

import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * 공성 시간대. docs/design/nation.md 5-1-2 (2026-11-22).
 * 플레이어가 국가 코어를 깎을 수 있는 시간. 기본 20:00~25:00(다음 날 01:00).
 * 서버가 활동 시간대를 조사해 평일·주말을 따로 '동시 접속이 가장 많은 연속 5시간'으로 옮긴다.
 */
data class Window(val startHour: Int, val lengthHours: Int = SiegeWindow.LENGTH_HOURS) {
    init { require(startHour in 0..23 && lengthHours in 1..24) }
    /** 이 창을 그날 [hour] 시에 열려 있는지(시작한 날 기준 시각, 24 이상은 다음 날). */
    fun coversHourOfStartDay(hourOfStartDay: Int): Boolean = hourOfStartDay in startHour until startHour + lengthHours
    val label: String get() = "%02d:00~%02d:00".format(startHour, startHour + lengthHours)
}

object SiegeWindow {
    const val LENGTH_HOURS = 5
    val DEFAULT = Window(20)
    /** 시작 시각 후보. 새벽 공성이 되지 않게 16시~22시 시작만 고른다(끝은 최대 다음 날 03:00). */
    val START_RANGE = 16..22
    /** 지금 창보다 10% 이상 나을 때만 옮긴다(자주 흔들리지 않게). */
    const val MIN_GAIN = 0.10
    /** 조사 기간과 최소 표본. 표본이 모자라면 기본값. */
    const val SURVEY_DAYS = 14
    const val MIN_WEEKDAY_SAMPLES = 5
    const val MIN_WEEKEND_SAMPLES = 2
    /** 바뀐 창은 알린 뒤 24시간이 지나야 적용한다. */
    const val NOTICE_HOURS = 24L

    fun isWeekend(day: DayOfWeek): Boolean = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY

    /** [now](서버 현지 시각)에 공성 시간인지. 자정을 넘는 창은 시작한 날(평일/주말)의 창을 따른다. */
    fun isOpen(now: LocalDateTime, weekday: Window, weekend: Window): Boolean {
        val today = if (isWeekend(now.dayOfWeek)) weekend else weekday
        if (today.coversHourOfStartDay(now.hour)) return true
        val yesterday = if (isWeekend(now.dayOfWeek.minus(1))) weekend else weekday
        return yesterday.coversHourOfStartDay(now.hour + 24)
    }

    /** 시간별(0~23시) 평균 동시 접속(잠수 제외)에서 [window] 의 합. */
    fun score(hourly: DoubleArray, window: Window): Double {
        require(hourly.size == 24)
        return (0 until window.lengthHours).sumOf { hourly[(window.startHour + it) % 24] }
    }

    /** 후보 중 합이 가장 큰 창. 같으면 [current] 를, 아니면 이른 시작을 고른다. */
    fun best(hourly: DoubleArray, current: Window = DEFAULT): Window {
        var bestW = current
        var bestS = if (current.startHour in START_RANGE) score(hourly, current) else Double.NEGATIVE_INFINITY
        for (h in START_RANGE) {
            val w = Window(h, current.lengthHours)
            val s = score(hourly, w)
            if (s > bestS + 1e-9) { bestW = w; bestS = s }
        }
        return bestW
    }

    /** 매일 06:00 재계산: 표본이 충분하고 [MIN_GAIN] 이상 나을 때만 새 창을 낸다(아니면 [current]). */
    fun next(hourly: DoubleArray, samples: Int, minSamples: Int, current: Window): Window {
        if (samples < minSamples) return current
        val b = best(hourly, current)
        val cur = score(hourly, current)
        return if (b != current && score(hourly, b) >= cur * (1 + MIN_GAIN) + 1e-9) b else current
    }
}
