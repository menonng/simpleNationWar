package dev.menonng.snw.panel

import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle
import org.jline.utils.Status
import java.nio.file.Path
import java.time.LocalTime
import java.util.concurrent.CountDownLatch

/**
 * Paper 콘솔처럼 쓰는 CLI. 서버 로그는 위로 흐르고(수준별 색), 맨 아래에 입력 줄과 상태 줄이 붙어 있다.
 * 점(.)으로 시작하는 줄은 관리 명령, 나머지는 서버 명령으로 보낸다.
 */
class Cli(private val sup: Supervisor, private val configFile: Path) {
    private val terminal: Terminal = TerminalBuilder.builder().system(true).name("snw-panel").build()
    private val reader: LineReader
    private val status: Status? = runCatching { Status.getStatus(terminal) }.getOrNull()
    @Volatile private var exiting = false
    private var lastInterrupt = 0L

    private val wrapperCommands = linkedMapOf(
        ".help" to "관리 명령 목록",
        ".start" to "서버 켜기",
        ".stop" to "서버 끄기(저장 후)",
        ".restart" to "재시작 [이유]  (재시작 전 자동 백업)",
        ".kill" to "강제 종료(저장 안 함)",
        ".status" to "상태·접속자·TPS·MSPT·설정",
        ".players" to "접속자 목록",
        ".backup" to "월드 백업(켜져 있어도 됨, 최근 N개 보관)",
        ".schedule" to "정기 재시작 시각 보기/바꾸기: .schedule 06:00 | .schedule off",
        ".autorestart" to "비정상 종료 시 자동 재시작: .autorestart on|off",
        ".grep" to "최근 로그 검색: .grep <글자>",
        ".whitelist" to "화이트리스트: .whitelist [list] | add <이름> | remove <이름> | on | off | reload  (꺼져 있어도 됨)",
        ".season" to "시즌: .season start | .season status",
        ".config" to "설정 보기, 서버 폴더 바꾸기: .config dir <폴더>",
        ".clear" to "화면 지우기",
        ".exit" to "서버를 끄고 나가기",
    )

    init {
        reader = LineReaderBuilder.builder()
            .terminal(terminal)
            .appName("snw-panel")
            .completer(PanelCompleter())
            .variable(LineReader.HISTORY_FILE, configFile.resolveSibling(".snw-panel-history"))
            .variable(LineReader.HISTORY_SIZE, 2000)
            .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
            .build()
    }

    /** Supervisor 가 부르는 출력. 입력 줄을 깨지 않도록 위로 끼워 넣는다. */
    fun print(line: String, level: LogLevel, panel: Boolean) {
        val sb = AttributedStringBuilder()
        if (panel) sb.styled(AttributedStyle.DEFAULT.foreground(0x55, 0xBB, 0x44).bold(), "[관리] ")
        val style = when (level) {
            LogLevel.WARN -> AttributedStyle.DEFAULT.foreground(0xFF, 0xCC, 0x11)
            LogLevel.ERROR -> AttributedStyle.DEFAULT.foreground(0xFF, 0x00, 0x45)
            LogLevel.CHAT -> AttributedStyle.DEFAULT.foreground(0x39, 0xC5, 0xBB)
            else -> AttributedStyle.DEFAULT
        }
        sb.styled(style, line)
        val s = sb.toAttributedString()
        if (reader.isReading) reader.printAbove(s) else { terminal.writer().println(s.toAnsi(terminal)); terminal.flush() }
    }

    fun run(autostart: Boolean) {
        print("simpleNationWar 서버 관리 — 점(.)으로 시작하면 관리 명령, 나머지는 서버 명령. .help 로 목록", LogLevel.INFO, true)
        if (autostart) sup.start()
        val statusThread = Thread({
            while (!exiting) {
                runCatching { status?.update(listOf(statusLine())) }
                Thread.sleep(1000)
            }
        }, "panel-status").apply { isDaemon = true; start() }
        while (!exiting) {
            val line = try {
                reader.readLine("> ")
            } catch (e: UserInterruptException) {
                val now = System.currentTimeMillis()
                if (now - lastInterrupt < 2000) { exit(); break }
                lastInterrupt = now
                print("Ctrl+C 를 한 번 더 누르면 서버를 끄고 나간다", LogLevel.WARN, true)
                continue
            } catch (e: EndOfFileException) {
                exit(); break
            }
            handle(line.trim())
        }
        statusThread.interrupt()
        runCatching { status?.update(emptyList()) }
        terminal.close()
    }

    private fun statusLine(): AttributedString {
        val sb = AttributedStringBuilder()
        val color = when (sup.state) {
            Supervisor.State.RUNNING -> AttributedStyle.DEFAULT.foreground(0x55, 0xBB, 0x44)
            Supervisor.State.STOPPED -> AttributedStyle.DEFAULT.foreground(0xFF, 0x00, 0x45)
            else -> AttributedStyle.DEFAULT.foreground(0xFF, 0xCC, 0x11)
        }
        sb.styled(color.bold(), "● ${sup.state.label}")
        val up = sup.startedAt?.takeIf { sup.running }?.let { java.time.Duration.between(it, java.time.Instant.now()) }
        sb.append("  접속 ${sup.players.size}명  TPS ${sup.tps?.let { "%.1f".format(it) } ?: "-"}  MSPT ${sup.mspt?.let { "%.1f".format(it) } ?: "-"}")
        sb.append("  가동 ${up?.let { "%d:%02d".format(it.toHours(), it.toMinutesPart()) } ?: "-"}")
        sb.append("  재시작 ${sup.dailyRestart ?: "끔"}")
        return sb.toAttributedString()
    }

    private fun handle(line: String) {
        if (line.isEmpty()) return
        if (!line.startsWith(".")) { sup.send(line.removePrefix("/")); return }
        val parts = line.split(Regex("\\s+"), limit = 3)
        val arg = parts.getOrNull(1)
        when (parts[0]) {
            ".help" -> wrapperCommands.forEach { (k, v) -> print("%-13s %s".format(k, v), LogLevel.INFO, true) }
            ".start" -> sup.start()
            ".stop" -> sup.stop()
            ".restart" -> sup.restart(line.removePrefix(".restart").trim().ifEmpty { "관리자 요청" })
            ".kill" -> if (confirm("저장하지 않고 강제로 끌까요?")) sup.kill()
            ".status" -> sup.status().forEach { print(it, LogLevel.INFO, true) }
            ".players" -> print("접속 ${sup.players.size}명: " + synchronized(sup.players) { sup.players.joinToString(", ") }, LogLevel.INFO, true)
            ".backup" -> sup.backup()
            ".schedule" -> when (arg) {
                null -> print("정기 재시작: ${sup.dailyRestart ?: "끔"}", LogLevel.INFO, true)
                "off" -> { sup.dailyRestart = null; saveConfig(); print("정기 재시작을 끈다", LogLevel.INFO, true) }
                else -> runCatching { LocalTime.parse(arg) }.onSuccess { sup.dailyRestart = it; saveConfig(); print("정기 재시작: $it (10·5·1분 전 경고)", LogLevel.INFO, true) }
                    .onFailure { print("시각 형식: HH:mm (예 06:00)", LogLevel.WARN, true) }
            }
            ".autorestart" -> when (arg) {
                "on" -> { sup.autoRestart = true; saveConfig(); print("자동 재시작 켬", LogLevel.INFO, true) }
                "off" -> { sup.autoRestart = false; saveConfig(); print("자동 재시작 끔", LogLevel.INFO, true) }
                else -> print("자동 재시작: ${if (sup.autoRestart) "켬" else "끔"}  (.autorestart on|off)", LogLevel.INFO, true)
            }
            ".grep" -> {
                val q = line.removePrefix(".grep").trim()
                if (q.isEmpty()) { print("사용: .grep <글자>", LogLevel.WARN, true); return }
                val hits = synchronized(sup.recent) { sup.recent.filter { it.contains(q, ignoreCase = true) } }
                hits.takeLast(30).forEach { print(it, LogParser.level(it), false) }
                print("'$q' ${hits.size}줄 (최근 30줄만 표시)", LogLevel.INFO, true)
            }
            ".whitelist" -> whitelist(arg, parts.getOrNull(2)?.trim())
            ".season" -> when (arg) {
                "start" -> if (confirm("시즌을 시작할까요? 모두 국가 스폰 지점으로 이동합니다.")) sup.send("snw season start")
                "status" -> sup.send("snw season status")
                else -> print("사용: .season start | .season status", LogLevel.WARN, true)
            }
            ".config" -> if (arg == "dir" && parts.size == 3) {
                sup.config = sup.config.copy(serverDir = Path.of(parts[2]).toAbsolutePath())
                saveConfig()
                print("서버 폴더: ${sup.config.serverDir}", LogLevel.INFO, true)
            } else {
                print("설정 파일 $configFile", LogLevel.INFO, true)
                print("명령 ${sup.config.command().joinToString(" ")}", LogLevel.INFO, true)
            }
            ".clear" -> { terminal.puts(org.jline.utils.InfoCmp.Capability.clear_screen); terminal.flush() }
            ".exit" -> exit()
            else -> print("모르는 관리 명령: ${parts[0]}  (.help)", LogLevel.WARN, true)
        }
    }

    /**
     * 켜져 있으면 바닐라 whitelist 명령(결과는 서버 로그로 나온다), 꺼져 있으면 파일을 직접 고친다.
     * 꺼져 있을 때의 add 는 이름으로 UUID 를 찾아야 하므로(인터넷) 뒤에서 돌린다.
     */
    private fun whitelist(sub: String?, name: String?) {
        val dir = sup.config.serverDir
        val live = sup.state == Supervisor.State.RUNNING
        when (sub ?: "list") {
            "list" -> if (live) sup.send("whitelist list") else {
                val list = Whitelist.read(dir)
                print("화이트리스트 ${when (Whitelist.enabled(dir)) { true -> "켬"; false -> "끔"; null -> "(설정 없음)" }} · ${list.size}명" +
                    (if (list.isNotEmpty()) ": " + list.joinToString(", ") { it.name } else ""), LogLevel.INFO, true)
            }
            "add" -> when {
                name == null || !Whitelist.NAME.matches(name) -> print("사용: .whitelist add <마인크래프트 이름>  (영문·숫자·_ 3~16자)", LogLevel.WARN, true)
                live -> sup.send("whitelist add $name")
                else -> Thread({
                    print("$name 의 계정을 찾는 중…", LogLevel.INFO, true)
                    val r = Whitelist.add(dir, name) { MojangProfiles.lookup(it) }
                    val (msg, lv) = when (r) {
                        Whitelist.AddResult.ADDED -> "화이트리스트에 넣었다: $name (서버를 켜면 바로 적용)" to LogLevel.INFO
                        Whitelist.AddResult.ALREADY -> "이미 화이트리스트에 있다: $name" to LogLevel.INFO
                        Whitelist.AddResult.NOT_FOUND -> "그런 계정이 없거나 조회에 실패했다: $name" to LogLevel.WARN
                        Whitelist.AddResult.BAD_NAME -> "이름 형식이 맞지 않다: $name" to LogLevel.WARN
                    }
                    print(msg, lv, true)
                }, "panel-whitelist").apply { isDaemon = true; start() }
            }
            "remove" -> when {
                name == null -> print("사용: .whitelist remove <이름>", LogLevel.WARN, true)
                live -> sup.send("whitelist remove $name")
                Whitelist.remove(dir, name) -> print("화이트리스트에서 뺐다: $name", LogLevel.INFO, true)
                else -> print("화이트리스트에 없다: $name", LogLevel.WARN, true)
            }
            "on", "off" -> {
                val on = sub == "on"
                if (live) { sup.send("whitelist $sub"); sup.pendingWhitelist = on } else Whitelist.setEnabled(dir, on)
                print("화이트리스트 ${if (on) "켬 (명단에 없는 사람은 접속할 수 없다)" else "끔"}", LogLevel.INFO, true)
            }
            "reload" -> if (live) sup.send("whitelist reload") else print("서버가 꺼져 있으면 켤 때 파일을 읽으므로 필요 없다", LogLevel.INFO, true)
            else -> print("사용: .whitelist [list] | add <이름> | remove <이름> | on | off | reload", LogLevel.WARN, true)
        }
    }

    private fun confirm(question: String): Boolean = runCatching {
        reader.readLine("$question (y/N) ").trim().lowercase() in setOf("y", "yes", "ㅛ", "네", "예")
    }.getOrDefault(false)

    private fun saveConfig() {
        sup.config = sup.config.copy(autoRestart = sup.autoRestart, dailyRestart = sup.dailyRestart)
        PanelConfig.save(configFile, sup.config)
    }

    private fun exit() {
        if (exiting) return
        if (sup.running) {
            print("서버를 끄는 중… (최대 90초)", LogLevel.INFO, true)
            val done = CountDownLatch(1)
            sup.stop { done.countDown() }
            done.await()
        }
        sup.shutdownTimer()
        exiting = true
    }

    /** 첫 낱말은 서버 명령(help 로 모은 것)·관리 명령, 그 뒤는 접속자 이름과 자주 쓰는 하위 명령. */
    private inner class PanelCompleter : Completer {
        private val sub = mapOf(
            "snw" to listOf("season", "spawn", "member", "transfer", "nation", "claim", "relation", "landmark", "reload"),
            "zomapo" to listOf("level", "horde", "bloodmoon", "nest", "reload", "debug"),
            "spark" to listOf("tps", "health", "profiler", "gc"),
            ".season" to listOf("start", "status"),
            ".autorestart" to listOf("on", "off"),
            ".schedule" to listOf("off", "06:00"),
            ".config" to listOf("dir"),
            ".whitelist" to listOf("list", "add", "remove", "on", "off", "reload"),
            "whitelist" to listOf("list", "add", "remove", "on", "off", "reload"),
            "season" to listOf("start", "status"),
            "gamerule" to emptyList(),
        )

        override fun complete(reader: LineReader, line: org.jline.reader.ParsedLine, candidates: MutableList<Candidate>) {
            val words = line.words()
            if (line.wordIndex() == 0) {
                (sup.serverCommands + Completion.COMMANDS.map { it.substringBefore(' ') }).distinct().forEach { candidates += Candidate(it) }
                wrapperCommands.forEach { (k, v) -> candidates += Candidate(k, k, "관리", v, null, null, true) }
                return
            }
            val prev = words.getOrNull(line.wordIndex() - 1) ?: ""
            sub[prev]?.forEach { candidates += Candidate(it) }
            if (prev == "remove" && words.firstOrNull() in setOf(".whitelist", "whitelist")) {
                Whitelist.read(sup.config.serverDir).forEach { candidates += Candidate(it.name, it.name, "화이트리스트", null, null, null, true) }
                return
            }
            synchronized(sup.players) { sup.players.forEach { candidates += Candidate(it, it, "접속자", null, null, null, true) } }
        }
    }
}

fun runCli(config: PanelConfig, configFile: Path, autostart: Boolean) {
    var cli: Cli? = null
    val pending = java.util.concurrent.ConcurrentLinkedQueue<Triple<String, LogLevel, Boolean>>()
    val sup = Supervisor(config) { line, level, panel ->
        val c = cli
        if (c == null) pending += Triple(line, level, panel) else c.print(line, level, panel)
    }
    val c = Cli(sup, configFile)
    cli = c
    while (true) { val p = pending.poll() ?: break; c.print(p.first, p.second, p.third) }
    c.run(autostart)
    System.exit(0)
}
