package dev.menonng.snw.bot

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path

/**
 * 봇 설정(config.json). 토큰·비밀은 환경 변수(SNW_BOT_TOKEN, SNW_WS_SECRET)로만 받는다.
 * 채널·역할 id 는 디스코드 서버에서 만든 뒤 적는다. 비어 있으면 그 기능은 조용히 건너뛴다.
 */
data class BotConfig(
    var guildId: String = "",
    var wsHost: String = "127.0.0.1",
    var wsPort: Int = 7341,
    var dbPath: String = "snw-bot.db",
    var channels: Channels = Channels(),
    var roles: Roles = Roles(),
    var diaryHour: Int = 6,
    var statusMinute: Int = 5,
    var alerts: Boolean = true,
    /** 시험 모드: /테스트 명령(가짜 멤버로 평가·추첨 흐름을 혼자 시험). 실제 시즌에는 끈다. 시험용 dbPath 를 따로 쓰길 권한다. */
    var testMode: Boolean = false,
) {
    data class Channels(var register: String = "", var notice: String = "", var status: String = "", var diary: String = "", var alerts: String = "", var admin: String = "")
    data class Roles(var unregistered: String = "", var survivor: String = "", var admin: String = "", var nations: MutableMap<String, String> = linkedMapOf())

    companion object {
        private val gson = GsonBuilder().setPrettyPrinting().create()
        fun load(path: Path): BotConfig =
            if (Files.exists(path)) gson.fromJson(Files.readString(path), BotConfig::class.java)
            else BotConfig().also { Files.writeString(path, gson.toJson(it)) }
    }
}
