package dev.menonng.snw.panel

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 서버 하나를 돌보는 쪽. CLI 가 쓴다. 콘솔 줄과 관리 메시지는 [out] 으로 내보낸다(어느 스레드에서든 불릴 수 있다).
 *
 * 하는 일: 켜기·끄기·재시작·강제 종료, 비정상 종료 시 자동 재시작, 매일 정기 재시작(10·5·1분 전 경고),
 * 재시작 때 백업, 1분마다 TPS·MSPT 조회(출력은 숨김), 켜진 직후 'help' 로 서버 명령 목록 수집(자동완성용).
 */
class Supervisor(
    @Volatile var config: PanelConfig,
    private val out: (String, LogLevel, Boolean) -> Unit,   // (줄, 수준, 관리 메시지인가)
) {
    enum class State(val label: String) { STOPPED("중지"), STARTING("시작 중"), RUNNING("실행 중"), STOPPING("끄는 중") }

    @Volatile var state = State.STOPPED
        private set
    val players: MutableSet<String> = java.util.Collections.synchronizedSet(linkedSetOf())
    val serverCommands: MutableSet<String> = java.util.Collections.synchronizedSortedSet(sortedSetOf())
    @Volatile var tps: Double? = null
        private set
    @Volatile var mspt: Double? = null
        private set
    @Volatile var startedAt: Instant? = null
        private set
    @Volatile var autoRestart = config.autoRestart
    @Volatile var dailyRestart: LocalTime? = config.dailyRestart
    /** 켜져 있을 때 .whitelist on/off 한 값. 서버는 꺼질 때 server.properties 를 자기 값으로 다시 쓰므로, 꺼진 뒤 다시 맞춘다. */
    @Volatile var pendingWhitelist: Boolean? = null
    val recent = java.util.ArrayDeque<String>()   // 최근 콘솔 줄(검색용)

    private var server: ServerProcess? = null
    @Volatile private var restarting = false
    @Volatile private var onStopped: (() -> Unit)? = null
    @Volatile private var hideSparkUntil = 0L
    @Volatile private var collectHelpUntil = 0L
    @Volatile private var expectTps = false
    @Volatile private var lastWarnMinute = -1
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "panel-timer").apply { isDaemon = true } }
    private var secondsSincePoll = 0

    init {
        timer.scheduleAtFixedRate({ runCatching { tick() } }, 1, 1, TimeUnit.SECONDS)
    }

    val running: Boolean get() = server?.alive == true

    fun info(msg: String) = out(msg, LogLevel.INFO, true)
    fun warn(msg: String) = out(msg, LogLevel.WARN, true)
    fun error(msg: String) = out(msg, LogLevel.ERROR, true)

    fun start(): Boolean {
        if (running) { warn("이미 켜져 있다"); return false }
        val jar = config.serverDir.resolve(config.jar)
        if (!Files.exists(jar)) { error("서버 jar 가 없다: $jar  (.config dir <폴더> 로 서버 폴더 지정)"); return false }
        players.clear(); tps = null; mspt = null
        state = State.STARTING
        val p = ServerProcess(config, ::onLine, ::onExit)
        server = p
        return runCatching { p.start(); startedAt = p.startedAt; info("서버를 켠다: ${config.command().joinToString(" ")}"); true }
            .getOrElse { state = State.STOPPED; error("시작 실패: ${it.message}"); false }
    }

    /** 끝나면 [then] 을 부른다(정상 종료 대기 90초, 넘으면 강제 종료). */
    fun stop(then: (() -> Unit)? = null) {
        if (!running) { then?.invoke(); return }
        onStopped = then
        state = State.STOPPING
        server?.stop()
    }

    fun restart(reason: String) {
        if (!running) { start(); return }
        warn("재시작: $reason")
        restarting = true
        stop()
    }

    fun kill() { server?.kill() }

    /** 서버 명령 보내기. 켜지는 중에는 받지 않는다. */
    fun send(cmd: String, quiet: Boolean = false): Boolean {
        if (!running) { if (!quiet) warn("서버가 꺼져 있다 (.start 로 켠다)"); return false }
        if (state != State.RUNNING) { if (!quiet) warn("아직 켜지는 중이다. 'Done' 이 뜬 뒤에 입력한다"); return false }
        if (cmd.startsWith("spark")) hideSparkUntil = 0
        server?.send(cmd)
        return true
    }

    fun status(): List<String> {
        val up = startedAt?.takeIf { running }?.let { Duration.between(it, Instant.now()) }
        return listOf(
            "상태      ${state.label}" + (up?.let { "  (가동 %d시간 %d분)".format(it.toHours(), it.toMinutesPart()) } ?: ""),
            "접속      ${players.size}명" + (if (players.isNotEmpty()) "  " + synchronized(players) { players.joinToString(", ") } else ""),
            "TPS·MSPT  ${tps?.let { "%.1f".format(it) } ?: "-"} · ${mspt?.let { "%.1fms".format(it) } ?: "-"}",
            "자동 재시작 ${if (autoRestart) "켬" else "끔"} · 정기 재시작 ${dailyRestart ?: "끔"}",
            "화이트리스트 ${when (Whitelist.enabled(config.serverDir)) { true -> "켬"; false -> "끔"; null -> "-" }} · ${Whitelist.read(config.serverDir).size}명",
            "서버 폴더 ${config.serverDir}",
        )
    }

    /** 월드 폴더를 zip 으로. 켜져 있으면 저장을 잠시 멈추고 뜬다. 끝나면 [then]. */
    fun backup(then: (() -> Unit)? = null) {
        val worldDir = config.serverDir.resolve(config.world)
        if (!Files.isDirectory(worldDir)) { warn("월드 폴더가 없다: $worldDir"); then?.invoke(); return }
        val live = running && state == State.RUNNING
        if (live) { server?.send("save-off"); server?.send("save-all flush") }
        Thread({
            if (live) Thread.sleep(5000)
            val dir = config.serverDir.resolve(config.backupDir).also { Files.createDirectories(it) }
            val target = dir.resolve(Backups.name(config.world, LocalDateTime.now()))
            info("백업 중… ${target.fileName}")
            val result = runCatching { zip(worldDir, target) }
            if (live) server?.send("save-on")
            val names = Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() }
            Backups.toDelete(names, config.world, config.backupKeep).forEach { Files.deleteIfExists(dir.resolve(it)) }
            result.onSuccess { info("백업 완료: $target (${Files.size(target) / 1_048_576}MB)") }.onFailure { error("백업 실패: ${it.message}") }
            then?.invoke()
        }, "panel-backup").start()
    }

    fun shutdownTimer() = timer.shutdownNow()

    // ------------------------------------------------------------------ 내부

    private fun onLine(raw: String) {
        val line = LogParser.strip(raw)
        val now = System.currentTimeMillis()
        val ev = LogParser.parse(line, expectTps)
        expectTps = ev is LogParser.Event.TpsHeader
        when (ev) {
            is LogParser.Event.Join -> players += ev.name
            is LogParser.Event.Leave -> players -= ev.name
            is LogParser.Event.Ready -> {
                state = State.RUNNING
                info("준비 완료 (${ev.seconds}초). 명령을 입력할 수 있다")
                collectHelpUntil = now + 3000
                server?.send("help")
                secondsSincePoll = 55   // 첫 TPS 조회는 5초 뒤
            }
            is LogParser.Event.Stopping -> state = State.STOPPING
            is LogParser.Event.Tps -> tps = ev.last1m
            is LogParser.Event.Mspt -> mspt = ev.median1m
            else -> {}
        }
        synchronized(recent) { recent.addLast(line); while (recent.size > 5000) recent.removeFirst() }
        if (now < collectHelpUntil) {
            Regex("""]: /(\S+)""").find(line)?.let { serverCommands += it.groupValues[1]; return }
        }
        if (now < hideSparkUntil && "[⚡]" in line) return
        out(line, LogParser.level(line), false)
    }

    private fun onExit(code: Int, requested: Boolean) {
        state = State.STOPPED
        players.clear()
        if (code == 0) info("서버가 꺼졌다") else error("서버가 꺼졌다 (종료 코드 $code)")
        pendingWhitelist?.let { on -> runCatching { Whitelist.setEnabled(config.serverDir, on) }; pendingWhitelist = null }
        val cb = onStopped
        onStopped = null
        when {
            restarting -> { restarting = false; backup { start() } }
            cb != null -> cb()
            !requested && autoRestart -> {
                warn("비정상 종료 → 10초 뒤 다시 켠다 (.autorestart off 로 끔)")
                timer.schedule({ if (!running) start() }, 10, TimeUnit.SECONDS)
            }
        }
    }

    private fun tick() {
        if (!running || state != State.RUNNING) return
        if (++secondsSincePoll >= 60) {
            secondsSincePoll = 0
            hideSparkUntil = System.currentTimeMillis() + 4000
            server?.send("spark tps")
        }
        val at = dailyRestart ?: return
        val now = LocalDateTime.now()
        val minuteKey = now.hour * 60 + now.minute
        RestartSchedule.warningAt(now, at)?.let { m ->
            if (minuteKey != lastWarnMinute) { lastWarnMinute = minuteKey; server?.send("say 정기 재시작까지 ${m}분 남았습니다.") }
        }
        if (now.toLocalTime().withSecond(0).withNano(0) == at && minuteKey != lastWarnMinute) {
            lastWarnMinute = minuteKey
            restart("정기 재시작")
        }
    }

    private fun zip(src: Path, target: Path) {
        ZipOutputStream(Files.newOutputStream(target)).use { zos ->
            Files.walk(src).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString() != "session.lock" }.forEach { f ->
                    zos.putNextEntry(ZipEntry(src.parent.relativize(f).toString().replace('\\', '/')))
                    Files.copy(f, zos)
                    zos.closeEntry()
                }
            }
        }
    }
}
