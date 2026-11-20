package dev.menonng.snw.panel

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Properties

/** 관리 창 설정. 처음 실행하면 panel.properties 를 만든다. */
data class PanelConfig(
    val serverDir: Path,
    val java: String = "java",
    val jar: String = "fabric-server-launch.jar",
    val heap: String = "8G",
    val extraJvm: String = "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions " +
        "-XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 " +
        "-XX:InitiatingHeapOccupancyPercent=15 -XX:ParallelGCThreads=4 -XX:ConcGCThreads=2",
    val autoRestart: Boolean = true,
    val dailyRestart: LocalTime? = LocalTime.of(6, 0),
    val backupDir: String = "backups",
    val backupKeep: Int = 7,
    val world: String = "world",
) {
    fun command(): List<String> =
        listOf(java, "-Xms$heap", "-Xmx$heap") + extraJvm.split(" ").filter { it.isNotBlank() } + listOf("-jar", jar, "nogui")

    companion object {
        fun load(file: Path): PanelConfig {
            val p = Properties()
            if (Files.exists(file)) Files.newBufferedReader(file).use { p.load(it) }
            val c = PanelConfig(
                serverDir = Path.of(p.getProperty("server.dir", ".")),
                java = p.getProperty("java", "java"),
                jar = p.getProperty("jar", "fabric-server-launch.jar"),
                heap = p.getProperty("heap", "8G"),
                extraJvm = p.getProperty("jvm.extra", PanelConfig(Path.of(".")).extraJvm),
                autoRestart = p.getProperty("restart.auto", "true").toBoolean(),
                dailyRestart = p.getProperty("restart.daily", "06:00").takeIf { it.isNotBlank() && it != "off" }?.let { LocalTime.parse(it) },
                backupDir = p.getProperty("backup.dir", "backups"),
                backupKeep = p.getProperty("backup.keep", "7").toInt(),
                world = p.getProperty("world", "world"),
            )
            if (!Files.exists(file)) save(file, c)
            return c
        }

        fun save(file: Path, c: PanelConfig) {
            val text = """
                # simpleNationWar 서버 관리 창 설정
                server.dir=${c.serverDir.toString().replace("\\", "/")}
                java=${c.java}
                jar=${c.jar}
                heap=${c.heap}
                jvm.extra=${c.extraJvm}
                # 서버가 비정상 종료되면 다시 켠다
                restart.auto=${c.autoRestart}
                # 매일 정기 재시작 시각(HH:mm), 끄려면 off. 10분·5분·1분 전에 경고
                restart.daily=${c.dailyRestart?.toString() ?: "off"}
                backup.dir=${c.backupDir}
                backup.keep=${c.backupKeep}
                world=${c.world}
            """.trimIndent() + "\n"
            Files.writeString(file, text)
        }
    }
}

enum class LogLevel { INFO, WARN, ERROR, CHAT, OTHER }

/** 서버 콘솔 한 줄을 읽어 상태를 갱신한다. 순수 함수라 테스트한다. */
object LogParser {
    private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    private val JOIN = Regex("""\]: (?:System chat: )?(\S+) joined the game""")
    private val LEAVE = Regex("""\]: (?:System chat: )?(\S+) left the game""")
    private val DONE = Regex("""\]: Done \(([\d.]+)s\)!""")
    private val STOPPING = Regex("""\]: Stopping (the )?server""")
    private val TPS = Regex("""TPS from last 5s, 10s, 1m, 5m, 15m:\s*$""")
    private val TPS_VALUES = Regex("""^\*?([\d.]+),\s*\*?([\d.]+),\s*\*?([\d.]+)""")
    private val MSPT = Regex("""([\d.]+)/([\d.]+)/([\d.]+)/([\d.]+);\s*([\d.]+)/([\d.]+)/([\d.]+)/([\d.]+)""")
    private val CHAT = Regex("""\]: (?:\[Not Secure\] )?<([^>]+)> """)

    fun strip(line: String): String = ANSI.replace(line, "")

    fun level(line: String): LogLevel = when {
        "/ERROR]" in line || "/FATAL]" in line -> LogLevel.ERROR
        "/WARN]" in line -> LogLevel.WARN
        CHAT.containsMatchIn(line) -> LogLevel.CHAT
        "/INFO]" in line -> LogLevel.INFO
        else -> LogLevel.OTHER
    }

    sealed interface Event {
        data class Join(val name: String) : Event
        data class Leave(val name: String) : Event
        data class Ready(val seconds: Double) : Event
        data object Stopping : Event
        data class Mspt(val median1m: Double, val p95_1m: Double) : Event
        data object TpsHeader : Event
        data class Tps(val last1m: Double) : Event
    }

    fun parse(raw: String, expectTps: Boolean = false): Event? {
        val line = strip(raw)
        JOIN.find(line)?.let { return Event.Join(it.groupValues[1]) }
        LEAVE.find(line)?.let { return Event.Leave(it.groupValues[1]) }
        DONE.find(line)?.let { return Event.Ready(it.groupValues[1].toDouble()) }
        if (STOPPING.containsMatchIn(line)) return Event.Stopping
        if (TPS.containsMatchIn(line)) return Event.TpsHeader
        MSPT.find(line)?.let { return Event.Mspt(it.groupValues[6].toDouble(), it.groupValues[7].toDouble()) }
        if (expectTps) {
            val body = line.substringAfter("]: ", line).substringAfter("[⚡]").trim()
            TPS_VALUES.find(body)?.let { return Event.Tps(it.groupValues[3].toDouble()) }
        }
        return null
    }
}

/** 명령 입력 기록(위·아래 화살표). */
class CommandHistory(private val max: Int = 200) {
    private val items = ArrayList<String>()
    private var cursor = 0

    fun add(cmd: String) {
        if (cmd.isBlank()) return
        if (items.lastOrNull() != cmd) items += cmd
        while (items.size > max) items.removeAt(0)
        cursor = items.size
    }

    fun previous(): String? = if (items.isEmpty()) null else items[(--cursor).coerceAtLeast(0).also { cursor = it }]

    fun next(): String? {
        if (cursor >= items.size - 1) { cursor = items.size; return "" }
        return items[++cursor]
    }
}

/** Tab 자동완성: 자주 쓰는 명령 + 접속 중인 플레이어 이름. */
object Completion {
    val COMMANDS = listOf(
        "list", "say ", "save-all", "stop", "kick ", "op ", "deop ", "whitelist add ", "whitelist remove ", "tp ",
        "gamerule ", "time set ", "weather clear", "spark tps", "spark health", "spark profiler start", "spark profiler stop",
        "snw season start", "snw season status", "snw spawn check", "snw spawn set ", "snw member ", "snw transfer open ",
        "zomapo level get", "zomapo horde ", "zomapo bloodmoon start", "zomapo reload",
    )

    fun complete(input: String, players: Collection<String>): List<String> {
        val lastSpace = input.lastIndexOf(' ')
        return if (lastSpace < 0) COMMANDS.filter { it.startsWith(input) }
        else {
            val head = input.substring(0, lastSpace + 1)
            val word = input.substring(lastSpace + 1)
            (players.filter { it.startsWith(word, ignoreCase = true) }.map { head + it } +
                COMMANDS.filter { it.startsWith(input) && it != input }).distinct()
        }
    }
}

/** 정기 재시작 일정: 남은 시간에 따라 경고를 낸다. */
object RestartSchedule {
    val WARN_MINUTES = listOf(10, 5, 1)

    fun nextRestart(now: LocalDateTime, at: LocalTime): LocalDateTime {
        val today = LocalDateTime.of(now.toLocalDate(), at)
        return if (today.isAfter(now)) today else LocalDateTime.of(now.toLocalDate().plusDays(1), at)
    }

    /** now 에 내보낼 경고(분). 해당 없으면 null. 같은 분에 한 번만 내도록 호출하는 쪽에서 막는다. */
    fun warningAt(now: LocalDateTime, at: LocalTime): Int? {
        val next = nextRestart(now.withSecond(0).withNano(0).minusSeconds(1), at)
        val minutes = java.time.Duration.between(now.withSecond(0).withNano(0), next).toMinutes().toInt()
        return minutes.takeIf { it in WARN_MINUTES }
    }
}

/** 백업 파일 이름과 보관 개수. */
object Backups {
    private val FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    fun name(world: String, t: LocalDateTime): String = "$world-${FMT.format(t)}.zip"

    /** 지울 백업 목록(오래된 것부터). [names] 는 백업 폴더의 파일 이름들. */
    fun toDelete(names: List<String>, world: String, keep: Int): List<String> =
        names.filter { it.startsWith("$world-") && it.endsWith(".zip") }.sorted().dropLast(keep)

    fun today(): LocalDate = LocalDate.now()
}
