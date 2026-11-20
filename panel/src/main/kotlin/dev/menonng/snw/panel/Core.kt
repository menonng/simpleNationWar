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

/**
 * 화이트리스트. 서버가 켜져 있으면 바닐라 `whitelist` 명령으로, 꺼져 있으면 `whitelist.json`·`server.properties` 를 직접 고친다.
 * 이름으로 UUID 를 찾는 일은 바닐라 서버와 같은 Mojang 프로필 API 를 쓴다([MojangProfiles]).
 */
data class WhitelistEntry(val uuid: String, val name: String)

object Whitelist {
    val NAME = Regex("^[A-Za-z0-9_]{3,16}$")
    private val OBJ = Regex("\\{[^{}]*\\}")
    private val UUID_F = Regex("\"uuid\"\\s*:\\s*\"([0-9a-fA-F-]{32,36})\"")
    private val NAME_F = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"")

    fun file(dir: Path): Path = dir.resolve("whitelist.json")

    fun parse(json: String): List<WhitelistEntry> = OBJ.findAll(json).mapNotNull { m ->
        val u = UUID_F.find(m.value)?.groupValues?.get(1) ?: return@mapNotNull null
        val n = NAME_F.find(m.value)?.groupValues?.get(1) ?: return@mapNotNull null
        WhitelistEntry(u, n)
    }.toList()

    /** 바닐라와 같은 모양(두 칸 들여쓰기)으로. */
    fun render(entries: List<WhitelistEntry>): String =
        if (entries.isEmpty()) "[]\n"
        else entries.joinToString(",\n", "[\n", "\n]\n") { "  {\n    \"uuid\": \"${it.uuid}\",\n    \"name\": \"${it.name}\"\n  }" }

    fun read(dir: Path): List<WhitelistEntry> =
        file(dir).takeIf { Files.exists(it) }?.let { parse(Files.readString(it)) } ?: emptyList()

    fun write(dir: Path, entries: List<WhitelistEntry>) { Files.writeString(file(dir), render(entries)) }

    enum class AddResult { ADDED, ALREADY, NOT_FOUND, BAD_NAME }

    fun add(dir: Path, name: String, resolve: (String) -> WhitelistEntry?): AddResult {
        if (!NAME.matches(name)) return AddResult.BAD_NAME
        val list = read(dir)
        if (list.any { it.name.equals(name, ignoreCase = true) }) return AddResult.ALREADY
        val e = resolve(name) ?: return AddResult.NOT_FOUND
        if (list.any { it.uuid.equals(e.uuid, ignoreCase = true) }) return AddResult.ALREADY
        write(dir, list + e)
        return AddResult.ADDED
    }

    fun remove(dir: Path, name: String): Boolean {
        val list = read(dir)
        val left = list.filterNot { it.name.equals(name, ignoreCase = true) }
        if (left.size == list.size) return false
        write(dir, left)
        return true
    }

    /** server.properties 의 white-list / enforce-whitelist. 없으면 null. */
    fun enabled(dir: Path): Boolean? = prop(dir, "white-list")?.toBooleanStrictOrNull()

    /** 켜고 끌 때 white-list 와 enforce-whitelist 를 함께 바꾼다(켜면 이미 접속한 사람 중 명단에 없는 사람도 내보냄). 다른 줄은 그대로. */
    fun setEnabled(dir: Path, on: Boolean) {
        val f = dir.resolve("server.properties")
        val lines = if (Files.exists(f)) Files.readAllLines(f).toMutableList() else mutableListOf()
        for (key in listOf("white-list", "enforce-whitelist")) {
            val i = lines.indexOfFirst { it.startsWith("$key=") }
            if (i >= 0) lines[i] = "$key=$on" else lines += "$key=$on"
        }
        Files.write(f, lines)
    }

    private fun prop(dir: Path, key: String): String? {
        val f = dir.resolve("server.properties")
        if (!Files.exists(f)) return null
        return Files.readAllLines(f).firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.trim()
    }

    /** 32자리 UUID 에 하이픈을 넣는다. */
    fun dashed(id: String): String = if (id.length != 32) id else
        "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}"
}

/** 이름 → UUID. 바닐라 서버가 `whitelist add` 때 묻는 곳과 같다. 없거나 실패하면 null. */
object MojangProfiles {
    fun lookup(name: String): WhitelistEntry? = runCatching {
        val client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()
        val req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://api.mojang.com/users/profiles/minecraft/$name"))
            .timeout(java.time.Duration.ofSeconds(8)).GET().build()
        val res = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200) return null
        val id = Regex("\"id\"\\s*:\\s*\"([0-9a-fA-F]{32})\"").find(res.body())?.groupValues?.get(1) ?: return null
        val n = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(res.body())?.groupValues?.get(1) ?: name
        WhitelistEntry(Whitelist.dashed(id), n)
    }.getOrNull()
}
