package dev.menonng.snw.logic.territory

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** 한 차원 안의 청크 좌표. */
data class Chunk(val x: Int, val z: Int) {
    fun neighbors4() = listOf(Chunk(x + 1, z), Chunk(x - 1, z), Chunk(x, z + 1), Chunk(x, z - 1))
    fun chebyshev(o: Chunk) = max(abs(x - o.x), abs(z - o.z))
}

data class TerritoryConfig(
    val baseCap: Int = 24,
    val perMember: Int = 6,
    val perLandmark: Int = 8,
    /** 다른 국가 영토와 사이에 비워야 하는 청크 수. */
    val foreignGap: Int = 2,
    val maxOutposts: Int = 2,
    val outpostMaxChunks: Int = 4,
    val outpostForeignGap: Int = 8,
)

enum class ClaimDenied(val message: String) {
    ALREADY_OWNED("이미 누군가의 땅입니다"),
    FORBIDDEN_ZONE("생존자 캠프나 거점 구역은 점령할 수 없습니다"),
    CAP_REACHED("영토 상한에 도달했습니다"),
    NOT_ADJACENT("기존 영토와 맞닿아 있어야 합니다"),
    TOO_CLOSE_TO_FOREIGN("다른 국가 영토와 2청크 이상 떨어져야 합니다"),
    OUTPOST_LIMIT("전초기지는 2개까지입니다"),
    OUTPOST_TOO_BIG("전초기지는 4청크까지입니다"),
    OUTPOST_TOO_CLOSE("전초기지는 다른 국가 영토와 8청크 이상 떨어져야 합니다"),
}

enum class UnclaimDenied(val message: String) {
    NOT_OURS("우리 땅이 아닙니다"),
    CAPITAL("수도 청크는 해제할 수 없습니다"),
    SPLITS_TERRITORY("해제하면 영토가 수도와 끊어집니다"),
}

/**
 * 영토 규칙. docs/design/nation.md 5절.
 * [mainland] 는 수도와 이어진 본토, [outposts] 는 본토와 떨어진 전초기지 묶음들.
 */
class TerritoryRules(val config: TerritoryConfig = TerritoryConfig()) {

    fun cap(members: Int, nationZombieScore: Double, landmarks: Int): Int =
        config.baseCap + config.perMember * members + floor(sqrt(max(0.0, nationZombieScore)) / 2).toInt() + config.perLandmark * landmarks

    fun checkClaim(
        target: Chunk,
        mainland: Set<Chunk>,
        outposts: List<Set<Chunk>>,
        foreign: Set<Chunk>,
        forbidden: Set<Chunk>,
        cap: Int,
        asNewOutpost: Boolean = false,
    ): ClaimDenied? {
        val owned = mainland.size + outposts.sumOf { it.size }
        if (target in mainland || outposts.any { target in it } || target in foreign) return ClaimDenied.ALREADY_OWNED
        if (target in forbidden) return ClaimDenied.FORBIDDEN_ZONE
        if (owned >= cap) return ClaimDenied.CAP_REACHED
        val nearForeign = { gap: Int -> foreign.any { it.chebyshev(target) <= gap } }

        val adjacentMain = target.neighbors4().any { it in mainland }
        val adjacentOutpost = outposts.firstOrNull { o -> target.neighbors4().any { it in o } }
        return when {
            adjacentMain -> if (nearForeign(config.foreignGap)) ClaimDenied.TOO_CLOSE_TO_FOREIGN else null
            adjacentOutpost != null -> when {
                adjacentOutpost.size >= config.outpostMaxChunks -> ClaimDenied.OUTPOST_TOO_BIG
                nearForeign(config.outpostForeignGap) -> ClaimDenied.OUTPOST_TOO_CLOSE
                else -> null
            }
            asNewOutpost -> when {
                outposts.size >= config.maxOutposts -> ClaimDenied.OUTPOST_LIMIT
                nearForeign(config.outpostForeignGap) -> ClaimDenied.OUTPOST_TOO_CLOSE
                else -> null
            }
            else -> ClaimDenied.NOT_ADJACENT
        }
    }

    fun checkUnclaim(target: Chunk, capital: Chunk, mainland: Set<Chunk>, outposts: List<Set<Chunk>>): UnclaimDenied? {
        if (outposts.any { target in it }) return null
        if (target !in mainland) return UnclaimDenied.NOT_OURS
        if (target == capital) return UnclaimDenied.CAPITAL
        val remaining = mainland - target
        return if (connectedFrom(capital, remaining).size == remaining.size) null else UnclaimDenied.SPLITS_TERRITORY
    }

    /** 다른 국가 영토에서 [range] 청크 안쪽인 접경 청크(감염 레벨 계산용). */
    fun borderRing(territory: Set<Chunk>, range: Int): Set<Chunk> {
        val out = HashSet<Chunk>()
        for (c in territory) for (dx in -range..range) for (dz in -range..range) {
            val n = Chunk(c.x + dx, c.z + dz)
            if (n !in territory) out += n
        }
        return out
    }

    companion object {
        fun connectedFrom(start: Chunk, set: Set<Chunk>): Set<Chunk> {
            if (start !in set) return emptySet()
            val seen = HashSet<Chunk>().apply { add(start) }
            val queue = ArrayDeque<Chunk>().apply { add(start) }
            while (queue.isNotEmpty()) {
                for (n in queue.removeFirst().neighbors4()) if (n in set && seen.add(n)) queue.add(n)
            }
            return seen
        }
    }
}
