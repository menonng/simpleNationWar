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

    @Test
    @DisplayName("화이트리스트: 파일 읽기·쓰기(바닐라 모양), 추가·중복·없는 계정·이름 형식·빼기, server.properties 켜고 끄기")
    fun case06() {
        val dir = Files.createTempDirectory("wl")
        assertTrue(Whitelist.read(dir).isEmpty())
        val fake = { n: String -> if (n.equals("Steve", true)) WhitelistEntry("8667ba71-b85a-4004-af54-457a9734eed7", "Steve") else null }
        assertEquals(Whitelist.AddResult.ADDED, Whitelist.add(dir, "Steve", fake))
        assertEquals(Whitelist.AddResult.ALREADY, Whitelist.add(dir, "steve", fake))
        assertEquals(Whitelist.AddResult.NOT_FOUND, Whitelist.add(dir, "Nobody_123", fake))
        assertEquals(Whitelist.AddResult.BAD_NAME, Whitelist.add(dir, "a b", fake))
        val text = Files.readString(Whitelist.file(dir))
        assertEquals("[\n  {\n    \"uuid\": \"8667ba71-b85a-4004-af54-457a9734eed7\",\n    \"name\": \"Steve\"\n  }\n]\n", text)
        // 바닐라가 쓴 파일(한 줄, 순서 반대)도 읽는다
        assertEquals(listOf(WhitelistEntry("853c80ef-3c37-49fd-aa49-938b674adae6", "jeb_")), Whitelist.parse("""[{"name":"jeb_","uuid":"853c80ef-3c37-49fd-aa49-938b674adae6"}]"""))
        assertTrue(Whitelist.remove(dir, "STEVE"))
        assertTrue(Whitelist.read(dir).isEmpty())
        Files.writeString(dir.resolve("server.properties"), "motd=hi\nwhite-list=false\n")
        Whitelist.setEnabled(dir, true)
        assertEquals(true, Whitelist.enabled(dir))
        assertEquals(listOf("motd=hi", "white-list=true", "enforce-whitelist=true"), Files.readAllLines(dir.resolve("server.properties")))
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", Whitelist.dashed("069a79f444e94726a5befca90e38aaf5"))
    }
}
