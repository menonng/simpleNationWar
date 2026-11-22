package dev.menonng.snw.logic

import dev.menonng.snw.logic.core.SiegeWindow
import dev.menonng.snw.logic.core.Window
import org.junit.jupiter.api.DisplayName
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SiegeWindowTest {
    private val d = SiegeWindow.DEFAULT

    @Test
    @DisplayName("기본 20:00~25:00, 자정을 넘으면 시작한 날의 창을 따른다")
    fun case01() {
        assertEquals("20:00~25:00", d.label)
        val fri = LocalDateTime.of(2027, 1, 22, 20, 0)            // 금요일
        assertTrue(SiegeWindow.isOpen(fri, d, d))
        assertFalse(SiegeWindow.isOpen(fri.withHour(19), d, d))
        assertTrue(SiegeWindow.isOpen(fri.plusDays(1).withHour(0), d, Window(16)))   // 토 00시 = 금요일 창
        assertFalse(SiegeWindow.isOpen(fri.plusDays(1).withHour(1), d, Window(16)))
        assertTrue(SiegeWindow.isOpen(fri.plusDays(1).withHour(16), d, Window(16)))  // 토 16시 = 주말 창
        assertFalse(SiegeWindow.isOpen(fri.plusDays(1).withHour(22), d, Window(16)))
    }

    @Test
    @DisplayName("시뮬레이션 접속 곡선(20명/활성 10명): 평일은 20시 유지, 주말은 16시로 옮긴다")
    fun case02() {
        fun curve(v14to1: DoubleArray) = DoubleArray(24).also { a -> v14to1.forEachIndexed { i, x -> a[(14 + i) % 24] = x } }
        val weekday = curve(doubleArrayOf(1.2, 1.6, 1.9, 2.3, 2.7, 3.3, 4.1, 4.8, 5.0, 4.4, 3.3, 2.1))
        val weekend = curve(doubleArrayOf(2.8, 3.6, 4.4, 5.1, 5.4, 5.2, 4.8, 4.2, 3.4, 2.5, 1.7, 1.2))
        assertEquals(d, SiegeWindow.next(weekday, 10, SiegeWindow.MIN_WEEKDAY_SAMPLES, d))         // 19시와 같으면 지금 유지
        assertEquals(Window(16), SiegeWindow.next(weekend, 4, SiegeWindow.MIN_WEEKEND_SAMPLES, d))
        assertEquals(d, SiegeWindow.next(weekend, 1, SiegeWindow.MIN_WEEKEND_SAMPLES, d))           // 표본 부족
        // 10% 미만 개선이면 옮기지 않는다
        val flat = DoubleArray(24) { 1.0 }.also { it[19] = 1.3 }
        assertEquals(d, SiegeWindow.next(flat, 10, 5, d))
    }
}
