package dev.menonng.snw.logic.discord

/**
 * #알림 채널 문장(docs/discord/README.md 5절). 사람은 [name] 으로 닉네임만(없으면 '누군가'), 좌표는 쓰지 않는다.
 * 보낼 문장이 없는 사건이면 null.
 */
object AlertText {
    @Suppress("UNCHECKED_CAST")
    fun render(type: String, data: Map<String, Any?>, name: (String?) -> String?): String? {
        fun who(id: Any?) = name(id as? String) ?: "누군가"
        return when (type) {
            Protocol.DEATH -> if (data["pvp"] == true) {
                val assists = (data["assists"] as? List<String>).orEmpty().mapNotNull { name(it) }
                "⚔ ${who(data["killer"])} → ${who(data["victim"])}" + if (assists.isNotEmpty()) " (어시스트: ${assists.joinToString(", ")})" else ""
            } else null
            Protocol.REVENANT -> "🧟 ${who(data["owner"])} 님이 망자가 되었습니다"
            Protocol.LANDMARK -> "🏴 ${data["landmark"]} 거점을 ${data["to"]}이(가) 점령했습니다"
            Protocol.VACCINE -> "🧪 백신 조각을 누군가 주웠습니다" + (data["total"]?.let { " (서버 전체 ${(it as Number).toInt()}개)" } ?: "")
            Protocol.BLOODMOON_NOTICE -> "🌕 블러드문이 ${(data["minutes"] as? Number)?.toInt() ?: 10}분 뒤 시작됩니다 — 습격 지점: ${(data["nations"] as? List<*>)?.joinToString(", ") ?: "모든 수도"}"
            Protocol.BLOODMOON_START -> "🩸 블러드문이 시작되었습니다"
            Protocol.BLOODMOON_RESULT -> {
                val rs = (data["results"] as? List<Map<String, Any?>>).orEmpty()
                "🩸 블러드문 끝 — " + rs.joinToString(", ") { r -> "${r["nation"]} ${if (r["success"] == true) "지켜 냄" else "실패"}(웨이브 ${(r["waves"] as? Number)?.toInt() ?: 0}/3)" }
            }
            Protocol.NEST -> if (data["event"] == "destroyed") "🔥 감염 둥지가 파괴되었습니다" else "🕳 새 감염 둥지가 발견되었습니다"
            Protocol.ALPHA -> "👑 알파가 쓰러졌습니다" + (data["nation"]?.let { " ($it)" } ?: "")
            Protocol.RAID -> "🔔 좀비화 습격을 막아 냈습니다 (징조 ${(data["omen"] as? Number)?.toInt() ?: 1}단계)"
            Protocol.STARFORCE -> "★ ${who(data["uuid"])} 님이 ${data["item"]} ${(data["star"] as? Number)?.toInt() ?: 10}성 강화를 달성했습니다"
            Protocol.FINALE -> if (data["event"] == "start") "🌑 마지막 밤, 최후의 호드가 시작되었습니다" else "🌑 마지막 밤까지 ${minutesText(data["minutes"])} 남았습니다"
            Protocol.ENDING -> if (data["result"] == "salvation") "💉 구원 — 백신이 완성되었습니다 (백신 조각 ${(data["shards"] as? Number)?.toInt() ?: 0}개)"
                               else "☠ 멸망 — 백신은 끝내 완성되지 못했습니다 (백신 조각 ${(data["shards"] as? Number)?.toInt() ?: 0}개)"
            Protocol.SEASON_START -> "🚩 시즌이 시작되었습니다"
            else -> null
        }
    }

    private fun minutesText(m: Any?): String {
        val v = (m as? Number)?.toLong() ?: return "곧"
        return if (v >= 60) "${v / 60}시간" else "${v}분"
    }
}
