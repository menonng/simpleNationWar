package dev.menonng.snw.bot

import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * snw 디스코드 봇. 환경 변수 SNW_BOT_TOKEN(디스코드), SNW_WS_SECRET(모드 연결), 설정 파일 config.json(인수로 경로를 줄 수 있다).
 * 토큰이 없으면 디스코드 없이 모드 연결·기록만 한다(시험용).
 */
fun main(args: Array<String>) {
    val cfg = BotConfig.load(Path.of(args.firstOrNull() ?: "config.json"))
    val secret = System.getenv("SNW_WS_SECRET")?.takeIf { it.isNotBlank() } ?: error("환경 변수 SNW_WS_SECRET 가 필요합니다")
    val token = System.getenv("SNW_BOT_TOKEN")?.takeIf { it.isNotBlank() }
    val db = Db("jdbc:sqlite:${cfg.dbPath}")
    val zone = ZoneId.of("Asia/Seoul")
    val front: Front
    var discord: DiscordFront? = null
    if (token != null) { discord = DiscordFront(cfg, token); front = discord } else {
        println("[snw-bot] SNW_BOT_TOKEN 이 없어 디스코드 없이 돕니다(알림은 표준 출력).")
        front = object : Front {
            override fun alert(text: String) = println("[알림] $text")
            override fun serverStatus(online: Boolean) = println("[상태] 서버 ${if (online) "켜짐" else "꺼짐"}")
            override fun linked(discordId: String, nickname: String) = println("[연동] $nickname")
        }
    }
    val hub = ModHub(db, front, zone = zone)
    discord?.hub = hub
    val ratings = RatingService(db, hub)
    val teams = TeamService(db, hub)
    hub.onRatingSubmit = { r, t, sc -> ratings.submitFromGame(r, t, sc) }
    val season = discord?.let { d -> SeasonFront(cfg, d, db, hub, ratings, teams).also { s -> d.extraCommands = s.commands(); d.jda.addEventListener(s) } }
    val ws = WsServer(cfg.wsHost, cfg.wsPort, secret, hub)
    ws.start()
    // 매일 아침: 일지(06:00), 상태(06:05)
    val sched = Executors.newSingleThreadScheduledExecutor()
    val diary = DiaryJob(db, zone)
    sched.scheduleAtFixedRate({
        val now = ZonedDateTime.now(zone)
        val today = now.toLocalDate().toString()
        if (now.hour == cfg.diaryHour && now.minute == 0 && db.get("diary.last") != today) runCatching {
            db.put("diary.last", today)
            val text = diary.write(LocalDate.now(zone).minusDays(1))
            discord?.daily(text) ?: println(text)
        }.onFailure { System.err.println("[snw-bot] 일지 오류: $it") }
        if (now.hour == cfg.diaryHour && now.minute == cfg.statusMinute && db.get("status.last") != today) runCatching {
            db.put("status.last", today)
            val text = hub.dailyStatus(LocalDate.now(zone).minusDays(1))
            discord?.status(text) ?: println(text)
            hub.requestStats()
        }
        runCatching { season?.tick() }.onFailure { System.err.println("[snw-bot] 평가 마감 확인 오류: $it") }
    }, 10, 30, TimeUnit.SECONDS)
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { ws.stop(1000) }; discord?.shutdown(); db.close() })
}
