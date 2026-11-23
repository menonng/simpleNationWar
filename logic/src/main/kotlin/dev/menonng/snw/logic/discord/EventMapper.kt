package dev.menonng.snw.logic.discord

/**
 * 서버 이벤트 버스(snw·zomApo 가 내는 사건) → 봇 메시지. 좌표·시각·채팅·마크 이름은 넘기지 않는다(privacy.md).
 * 알 수 없는 사건은 [Protocol.SERVER_EVENT] 로 이름과 '좌표 아닌 값'만 넘긴다(봇은 무시하거나 기록만).
 */
object EventMapper {
    data class Out(val type: String, val data: Map<String, Any?>)

    private val COORD_KEYS = setOf("x", "y", "z", "dimension", "dim", "pos", "position")

    fun map(busType: String, data: Map<String, Any?>): Out? = when (busType) {
        "vaccine_shard" -> Out(Protocol.VACCINE, mapOf("person" to data["player"], "source" to data["source"]))
        "bloodmoon_notice" -> Out(Protocol.BLOODMOON_NOTICE, mapOf("minutes" to data["minutes"], "nations" to data["nations"]))
        "bloodmoon_start" -> Out(Protocol.BLOODMOON_START, mapOf("nations" to data["nations"]))
        "bloodmoon_end" -> Out(Protocol.BLOODMOON_RESULT, mapOf("results" to data["results"]))
        "nest_discovered" -> Out(Protocol.NEST, mapOf("event" to "found", "nation" to data["nation"]))
        "nest_destroyed" -> Out(Protocol.NEST, mapOf("event" to "destroyed", "nation" to data["nation"]))
        "landmark_captured" -> Out(Protocol.LANDMARK, mapOf("landmark" to data["landmark"], "to" to data["nation"]))
        "alpha_slain" -> Out(Protocol.ALPHA, mapOf("event" to "slain", "nation" to data["nation"]))
        "zombified_raid_won" -> Out(Protocol.RAID, mapOf("omen" to data["omen"], "participants" to data["participants"]))
        "finale_notice" -> Out(Protocol.FINALE, mapOf("event" to "notice", "minutes" to data["minutes"]))
        "finale_start" -> Out(Protocol.FINALE, mapOf("event" to "start"))
        "ending" -> Out(Protocol.ENDING, mapOf("result" to data["result"], "shards" to data["shards"]))
        "infection_daily" -> Out(Protocol.DAILY, data.filterKeys { it !in COORD_KEYS })
        "season_start" -> Out(Protocol.SEASON_START, mapOf("date" to data["date"]))
        "starforce_success", "starforce_max" -> Out(Protocol.STARFORCE, mapOf("uuid" to data["player"], "item" to data["item"], "star" to data["star"]))
        "revenant_created" -> Out(Protocol.REVENANT, mapOf("owner" to data["owner"], "nation" to data["nation"]))
        "landmark_chest", "nickname_review" -> null           // 좌표가 든 내부 사건 / 운영자용
        else -> Out(Protocol.SERVER_EVENT, mapOf("name" to busType, "data" to data.filterKeys { it !in COORD_KEYS }))
    }
}
