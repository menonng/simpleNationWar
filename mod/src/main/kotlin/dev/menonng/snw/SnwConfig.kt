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
        # 국가 영토 안 정주민 상한과 하루(06:00 기준) 번식 수
        villager.cap=20
        villager.breed_per_day=4
        # 국고에서 한 번에 이만큼 넘게 꺼내면 원수·간부에게 알린다
        treasury.bulk_withdraw=64
    """.trimIndent() + "\n"

    var chatDefault: ChatChannel = ChatChannel.TEAM
        private set

    var villagerCap = 20
        private set
    var villagerBreedPerDay = 4
        private set
    var treasuryBulkWithdraw = 64
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
        villagerCap = props.getProperty("villager.cap", "20").trim().toIntOrNull() ?: 20
        villagerBreedPerDay = props.getProperty("villager.breed_per_day", "4").trim().toIntOrNull() ?: 4
        treasuryBulkWithdraw = props.getProperty("treasury.bulk_withdraw", "64").trim().toIntOrNull() ?: 64
        chatDefault = when (props.getProperty("chat.default", "team").trim().lowercase()) {
            "global" -> ChatChannel.GLOBAL
            else -> ChatChannel.TEAM
        }
    }
}
