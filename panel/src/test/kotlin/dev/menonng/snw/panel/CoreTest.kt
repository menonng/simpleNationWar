package dev.menonng.snw.panel

import org.junit.jupiter.api.DisplayName
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    @Test
    @DisplayName("콘솔 줄 해석: 접속·퇴장·준비·채팅·수준, ANSI 제거")
    fun case01() {
        assertEquals(LogParser.Event.Join("Steve"), LogParser.parse("[20:01:02] [Server thread/INFO]: Steve joined the game"))
        assertEquals(LogParser.Event.Leave("Steve"), LogParser.parse("\u001B[32m[20:01:02] [Server thread/INFO]: Steve left the game\u001B[0m"))
        assertEquals(LogParser.Event.Ready(2.589), LogParser.parse("[04:24:55] [Server thread/INFO]: Done (2.589s)! For help, type \"help\""))
        assertEquals(LogParser.Event.Stopping, LogParser.parse("[04:25:32] [Server thread/INFO]: Stopping the server"))
        assertEquals(LogLevel.ERROR, LogParser.level("[x] [Server thread/ERROR]: boom"))
        assertEquals(LogLevel.WARN, LogParser.level("[x] [Server thread/WARN]: Can't keep up!"))
        assertEquals(LogLevel.CHAT, LogParser.level("[x] [Server thread/INFO]: <Steve> hi"))
        // 26.x 형식: 시스템 메시지 앞에 'System chat: ', 서명 없는 채팅 앞에 '[Not Secure] '
        assertEquals(LogParser.Event.Join("Alex"), LogParser.parse("[10:45:11] [Server thread/INFO]: System chat: Alex joined the game"))
        assertEquals(LogParser.Event.Leave("Alex"), LogParser.parse("[10:45:11] [Server thread/INFO]: System chat: Alex left the game"))
        assertEquals(LogLevel.CHAT, LogParser.level("[x] [Server thread/INFO]: [Not Secure] <Steve> hi"))
    }

    @Test
    @DisplayName("spark tps 출력에서 TPS·MSPT 읽기")
    fun case02() {
        assertEquals(LogParser.Event.TpsHeader, LogParser.parse("[x] [spark-worker-pool-1-thread-1/INFO]: [⚡] TPS from last 5s, 10s, 1m, 5m, 15m:"))
        assertEquals(LogParser.Event.Tps(19.29), LogParser.parse("[x] [spark-worker-pool-1-thread-1/INFO]: [⚡]  *20.0, *20.0, 19.29, 19.85, 19.95", expectTps = true))
        assertEquals(LogParser.Event.Mspt(7.2, 41.0), LogParser.parse("[x] [spark/INFO]: [⚡]  4.8/5.7/8.0/9.7;  0.2/7.2/41.0/1923.3"))
    }

    @Test
    @DisplayName("명령 기록과 자동완성")
    fun case03() {
        val h = CommandHistory()
        h.add("list"); h.add("save-all"); h.add("save-all")
        assertEquals("save-all", h.previous())
        assertEquals("list", h.previous())
        assertEquals("list", h.previous())
        assertEquals("save-all", h.next())
        assertEquals("", h.next())
        assertTrue("snw season start" in Completion.complete("snw season", emptyList()))
        assertEquals(listOf("kick Steve"), Completion.complete("kick st", listOf("Steve", "Alex")))
    }

    @Test
    @DisplayName("정기 재시작 경고: 10·5·1분 전")
    fun case04() {
        val at = LocalTime.of(6, 0)
        assertEquals(10, RestartSchedule.warningAt(LocalDateTime.of(2027, 1, 20, 5, 50, 10), at))
        assertEquals(5, RestartSchedule.warningAt(LocalDateTime.of(2027, 1, 20, 5, 55, 0), at))
        assertEquals(1, RestartSchedule.warningAt(LocalDateTime.of(2027, 1, 20, 5, 59, 30), at))
        assertNull(RestartSchedule.warningAt(LocalDateTime.of(2027, 1, 20, 5, 57, 0), at))
        assertEquals(LocalDateTime.of(2027, 1, 21, 6, 0), RestartSchedule.nextRestart(LocalDateTime.of(2027, 1, 20, 7, 0), at))
    }

    @Test
    @DisplayName("백업 보관 개수와 설정 파일 왕복")
    fun case05() {
        val names = (1..9).map { "world-2027011${it}-0600.zip" } + "other.txt"
        assertEquals(listOf("world-20270111-0600.zip", "world-20270112-0600.zip"), Backups.toDelete(names, "world", 7))
        val f = Files.createTempFile("panel", ".properties"); Files.delete(f)
        val c = PanelConfig.load(f)
        assertTrue(Files.exists(f))
        assertEquals("8G", c.heap)
        assertEquals(LocalTime.of(6, 0), c.dailyRestart)
        assertTrue(c.command().containsAll(listOf("-Xmx8G", "-jar", "fabric-server-launch.jar", "nogui")))
    }
}
