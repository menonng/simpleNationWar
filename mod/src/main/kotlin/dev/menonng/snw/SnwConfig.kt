package dev.menonng.snw

import dev.menonng.snw.logic.chat.ChatChannel
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.util.Properties

/** config/snw.properties. 처음 켤 때 기본값으로 만든다. */
object SnwConfig {
    private val DEFAULT = """
        # simpleNationWar 설정
        # chat.default
        #   team   : 명령어 없이 친 채팅은 국가(바닐라 팀) 채팅. 전체 채팅은 /g <메시지> (기본)
        #   global : 바닐라처럼 전체 채팅. 국가 채팅은 /tm <메시지>
        chat.default=team
    """.trimIndent() + "\n"

    var chatDefault: ChatChannel = ChatChannel.TEAM
        private set

    fun load() {
        val file = FabricLoader.getInstance().configDir.resolve("snw.properties")
        val props = Properties()
        if (Files.exists(file)) {
            Files.newBufferedReader(file).use { props.load(it) }
        } else {
            Files.createDirectories(file.parent)
            Files.writeString(file, DEFAULT)
        }
        chatDefault = when (props.getProperty("chat.default", "team").trim().lowercase()) {
            "global" -> ChatChannel.GLOBAL
            else -> ChatChannel.TEAM
        }
    }
}
