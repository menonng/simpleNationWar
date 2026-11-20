package dev.menonng.snw.panel

import com.formdev.flatlaf.FlatDarkLaf
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextField
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

// 색: 사용자 팔레트에서 의미가 있는 곳에만(상태·경고·오류·채팅)
private val GREEN = Color(0x55BB44)
private val RED = Color(0xFF0045)
private val YELLOW = Color(0xFFCC11)
private val SKY = Color(0x39C5BB)

/** 서버 프로세스. 콘솔 줄은 [onLine], 종료는 [onExit] 로 알린다. */
class ServerProcess(private val config: PanelConfig, private val onLine: (String) -> Unit, private val onExit: (Int, Boolean) -> Unit) {
    private var process: Process? = null
    private var stdin: BufferedWriter? = null
    private val stopRequested = AtomicBoolean(false)
    var startedAt: Instant? = null
        private set

    val alive: Boolean get() = process?.isAlive == true

    fun start() {
        if (alive) return
        stopRequested.set(false)
        val pb = ProcessBuilder(config.command()).directory(config.serverDir.toFile()).redirectErrorStream(true)
        val p = pb.start()
        process = p
        startedAt = Instant.now()
        stdin = BufferedWriter(OutputStreamWriter(p.outputStream, StandardCharsets.UTF_8))
        Thread({
            p.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines -> lines.forEach(onLine) }
            val code = p.waitFor()
            onExit(code, stopRequested.get())
        }, "server-output").apply { isDaemon = true }.start()
    }

    fun send(cmd: String) {
        val w = stdin ?: return
        runCatching { w.write(cmd); w.newLine(); w.flush() }
    }

    /** 'stop' 을 보내고 [timeout] 동안 기다린 뒤에도 살아 있으면 강제 종료. */
    fun stop(timeout: Duration = Duration.ofSeconds(90)) {
        val p = process ?: return
        stopRequested.set(true)
        send("stop")
        Thread({
            if (!p.waitFor(timeout.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        }, "server-stop").apply { isDaemon = true }.start()
    }

    fun kill() {
        stopRequested.set(true)
        process?.destroyForcibly()
    }
}

class PanelWindow(private var config: PanelConfig, private val configFile: Path) : JFrame("simpleNationWar 서버 관리") {
    private val console = JTextPane().apply { isEditable = false; font = Font(Font.MONOSPACED, Font.PLAIN, 13) }
    private val input = JTextField()
    private val players = DefaultListModel<String>()
    private val status = JLabel("● 중지")
    private val info = JLabel("접속 0명 · TPS - · MSPT - · 가동 -")
    private val history = CommandHistory()
    private var server = newServer()
    private var tps = "-"
    private var mspt = "-"
    private var expectTps = false
    private var suppressSparkUntil = 0L
    private var lastWarnMinute = -1
    private var restarting = false
    private var ready = false

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        minimumSize = Dimension(980, 620)
        layout = BorderLayout()

        val top = JPanel(FlowLayout(FlowLayout.LEFT, 8, 6))
        status.foreground = RED
        status.font = status.font.deriveFont(Font.BOLD)
        top.add(status)
        top.add(button("시작") { startServer() })
        top.add(button("중지") { server.stop() })
        top.add(button("재시작") { restart("관리 창에서 재시작") })
        top.add(button("강제 종료") {
            if (confirm("저장하지 않고 강제로 끌까요?")) server.kill()
        })
        top.add(button("백업") { backup() })
        top.add(button("설정 폴더") { chooseServerDir() })
        top.add(info)
        add(top, BorderLayout.NORTH)

        val consolePanel = JPanel(BorderLayout())
        consolePanel.add(JScrollPane(console), BorderLayout.CENTER)
        input.toolTipText = "명령 입력 (위·아래: 기록, Tab: 자동완성). 앞의 / 는 붙이지 않아도 된다"
        consolePanel.add(input, BorderLayout.SOUTH)

        val side = JPanel(BorderLayout(0, 6))
        side.border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
        side.add(JLabel("접속 중"), BorderLayout.NORTH)
        side.add(JScrollPane(JList(players)), BorderLayout.CENTER)
        val quick = JPanel(java.awt.GridLayout(0, 1, 0, 4))
        quick.add(button("시즌 시작") {
            if (confirm("시즌을 시작할까요? 모두 국가 스폰 지점으로 이동합니다.")) command("snw season start")
        })
        quick.add(button("시즌 상태") { command("snw season status") })
        quick.add(button("저장") { command("save-all") })
        quick.add(button("TPS·MSPT") { command("spark tps") })
        quick.add(button("서버 상태") { command("spark health") })
        side.add(quick, BorderLayout.SOUTH)
        side.preferredSize = Dimension(220, 0)

        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, consolePanel, side).apply { resizeWeight = 1.0 }, BorderLayout.CENTER)

        input.addActionListener {
            val cmd = input.text.trim().removePrefix("/")
            if (cmd.isNotEmpty()) { history.add(cmd); command(cmd) }
            input.text = ""
        }
        input.focusTraversalKeysEnabled = false
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_UP -> history.previous()?.let { input.text = it }
                    KeyEvent.VK_DOWN -> history.next()?.let { input.text = it }
                    KeyEvent.VK_TAB -> {
                        val c = Completion.complete(input.text, (0 until players.size()).map { players[it] })
                        when (c.size) {
                            0 -> {}
                            1 -> input.text = c[0]
                            else -> append("자동완성: " + c.take(12).joinToString("  "), LogLevel.OTHER)
                        }
                    }
                }
            }
        })
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                if (!server.alive || confirm("서버가 켜져 있습니다. 서버를 끄고 창을 닫을까요?")) {
                    if (server.alive) server.stop()
                    dispose()
                    Thread { Thread.sleep(500); System.exit(0) }.start()
                }
            }
        })
        Timer(1000) { tick() }.start()
        Timer(60_000) { if (server.alive && ready) { suppressSparkUntil = System.currentTimeMillis() + 4000; server.send("spark tps") } }.start()
        pack()
        setLocationRelativeTo(null)
        if (!Files.exists(config.serverDir.resolve(config.jar))) {
            append("서버 jar 를 찾지 못했다: ${config.serverDir.resolve(config.jar)}  →  '설정 폴더' 로 서버 폴더를 고른다", LogLevel.WARN)
        }
    }

    private fun newServer() = ServerProcess(config, { line -> SwingUtilities.invokeLater { onLine(line) } }) { code, requested ->
        SwingUtilities.invokeLater { onExit(code, requested) }
    }

    private fun button(text: String, action: () -> Unit) = JButton(text).apply { addActionListener { action() } }

    private fun confirm(msg: String) = JOptionPane.showConfirmDialog(this, msg, "확인", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION

    private fun command(cmd: String) {
        if (!server.alive) { append("서버가 꺼져 있다", LogLevel.WARN); return }
        if (!ready) { append("서버가 아직 켜지는 중이다. 'Done' 이 뜬 뒤에 입력한다", LogLevel.WARN); return }
        if (cmd.startsWith("spark")) suppressSparkUntil = 0
        append("> $cmd", LogLevel.OTHER)
        server.send(cmd)
    }

    fun autostart() = startServer()

    private fun startServer() {
        if (server.alive) return
        if (!Files.exists(config.serverDir.resolve(config.jar))) {
            append("서버 jar 가 없다: ${config.serverDir.resolve(config.jar)}", LogLevel.ERROR)
            return
        }
        players.clear()
        ready = false
        setStatus("● 시작 중", YELLOW)
        server = newServer()
        runCatching { server.start() }.onFailure {
            append("시작 실패: ${it.message}", LogLevel.ERROR)
            setStatus("● 중지", RED)
        }
    }

    private fun restart(reason: String) {
        if (!server.alive) { startServer(); return }
        append("재시작: $reason", LogLevel.WARN)
        restarting = true
        server.stop()
    }

    private fun onLine(raw: String) {
        val line = LogParser.strip(raw)
        val now = System.currentTimeMillis()
        val ev = LogParser.parse(line, expectTps)
        expectTps = ev is LogParser.Event.TpsHeader
        when (ev) {
            is LogParser.Event.Join -> if (!players.contains(ev.name)) players.addElement(ev.name)
            is LogParser.Event.Leave -> players.removeElement(ev.name)
            is LogParser.Event.Ready -> { ready = true; setStatus("● 실행 중", GREEN) }
            is LogParser.Event.Stopping -> setStatus("● 끄는 중", YELLOW)
            is LogParser.Event.Tps -> tps = "%.1f".format(ev.last1m)
            is LogParser.Event.Mspt -> mspt = "%.1f".format(ev.median1m)
            else -> {}
        }
        if (now < suppressSparkUntil && "[⚡]" in line) return
        append(line, LogParser.level(line))
    }

    private fun onExit(code: Int, requested: Boolean) {
        ready = false
        setStatus("● 중지", RED)
        players.clear()
        append("서버가 꺼졌다 (종료 코드 $code)", if (code == 0) LogLevel.INFO else LogLevel.ERROR)
        when {
            restarting -> { restarting = false; backup(); startServer() }
            !requested && config.autoRestart -> {
                append("비정상 종료 → 10초 뒤 다시 켠다", LogLevel.WARN)
                Timer(10_000) { startServer() }.apply { isRepeats = false; start() }
            }
        }
    }

    private fun tick() {
        val up = server.startedAt?.takeIf { server.alive }?.let { Duration.between(it, Instant.now()) }
        val upText = up?.let { "%d:%02d".format(it.toHours(), it.toMinutesPart()) } ?: "-"
        info.text = "접속 ${players.size()}명 · TPS $tps · MSPT $mspt · 가동 $upText"
        val at = config.dailyRestart ?: return
        if (!server.alive) return
        val now = LocalDateTime.now()
        val warn = RestartSchedule.warningAt(now, at)
        val minuteKey = now.hour * 60 + now.minute
        if (warn != null && minuteKey != lastWarnMinute) {
            lastWarnMinute = minuteKey
            server.send("say 정기 재시작까지 ${warn}분 남았습니다.")
        }
        if (now.toLocalTime().withSecond(0).withNano(0) == at && minuteKey != lastWarnMinute) {
            lastWarnMinute = minuteKey
            restart("정기 재시작")
        }
    }

    /** 월드 폴더를 zip 으로. 서버가 켜져 있으면 저장을 잠시 멈추고 뜬다. */
    private fun backup() {
        val worldDir = config.serverDir.resolve(config.world)
        if (!Files.isDirectory(worldDir)) { append("월드 폴더가 없다: $worldDir", LogLevel.WARN); return }
        val live = server.alive
        if (live) { server.send("save-off"); server.send("save-all flush") }
        Thread {
            if (live) Thread.sleep(5000)
            val dir = config.serverDir.resolve(config.backupDir).also { Files.createDirectories(it) }
            val out = dir.resolve(Backups.name(config.world, LocalDateTime.now()))
            val result = runCatching { zip(worldDir, out) }
            if (live) server.send("save-on")
            val names = Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() }
            Backups.toDelete(names, config.world, config.backupKeep).forEach { Files.deleteIfExists(dir.resolve(it)) }
            SwingUtilities.invokeLater {
                result.onSuccess { append("백업 완료: $out (${Files.size(out) / 1_048_576}MB)", LogLevel.INFO) }
                    .onFailure { append("백업 실패: ${it.message}", LogLevel.ERROR) }
            }
        }.start()
    }

    private fun zip(src: Path, out: Path) {
        ZipOutputStream(Files.newOutputStream(out)).use { zos ->
            Files.walk(src).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString() != "session.lock" }.forEach { f ->
                    zos.putNextEntry(ZipEntry(src.parent.relativize(f).toString().replace('\\', '/')))
                    Files.copy(f, zos)
                    zos.closeEntry()
                }
            }
        }
    }

    private fun chooseServerDir() {
        val fc = JFileChooser(config.serverDir.toFile()).apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            config = config.copy(serverDir = fc.selectedFile.toPath())
            PanelConfig.save(configFile, config)
            append("서버 폴더: ${config.serverDir} (설정 저장: $configFile)", LogLevel.INFO)
        }
    }

    private fun setStatus(text: String, color: Color) { status.text = text; status.foreground = color }

    private fun append(text: String, level: LogLevel) {
        val doc = console.styledDocument
        val attr = SimpleAttributeSet()
        when (level) {
            LogLevel.WARN -> StyleConstants.setForeground(attr, YELLOW)
            LogLevel.ERROR -> StyleConstants.setForeground(attr, RED)
            LogLevel.CHAT -> StyleConstants.setForeground(attr, SKY)
            else -> {}
        }
        doc.insertString(doc.length, text + "\n", attr)
        val root = doc.defaultRootElement
        if (root.elementCount > 5000) doc.remove(0, root.getElement(root.elementCount - 5000).startOffset)
        console.caretPosition = doc.length
    }
}

/**
 * 사용: snw-panel [설정 파일] [--autostart] [--gui]
 *  기본은 Paper 콘솔 같은 CLI. --gui 는 창 버전. --autostart 는 바로 서버를 켠다.
 */
fun main(args: Array<String>) {
    val configFile = Path.of(args.firstOrNull { !it.startsWith("--") } ?: "panel.properties").toAbsolutePath()
    val config = PanelConfig.load(configFile)
    if ("--gui" !in args) { runCli(config, configFile, "--autostart" in args); return }
    FlatDarkLaf.setup()
    SwingUtilities.invokeLater {
        val w = PanelWindow(config, configFile)
        w.isVisible = true
        if ("--autostart" in args) w.autostart()
    }
}
