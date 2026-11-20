package dev.menonng.snw.logic.spawn

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 국가 스폰 지점과 국민 개인 스폰 지점. docs/design/nation.md 3절.
 *
 * - 국가 스폰 지점: (0,0) 에서 1,000 이상 3,000 미만 고리 안. 세 지점은 서로 충분히 떨어져야 한다.
 * - 개인 스폰 지점: 국가 스폰 지점에서 40~60블록 떨어진 땅 위. 시즌 시작 때 한 번 정해 저장한다.
 */
data class XZ(val x: Double, val z: Double) {
    fun dist(o: XZ): Double = hypot(x - o.x, z - o.z)
    val radius: Double get() = hypot(x, z)
    val angleDeg: Double get() = (Math.toDegrees(atan2(z, x)) + 360.0) % 360.0
}

data class XYZ(val x: Int, val y: Int, val z: Int)

object NationSpawn {
    const val MIN_RADIUS = 1000.0
    const val MAX_RADIUS = 3000.0          // 미만
    const val MIN_PAIR_DISTANCE = 1500.0   // 국가 스폰 사이 최소 거리
    const val MIN_ANGLE_GAP = 90.0         // 중심에서 본 각도 차 최소값(도)
    const val MAX_RADIUS_SPREAD = 400.0    // 세 지점의 중심 거리 차 최대값(공정성)

    fun inRing(p: XZ): Boolean = p.radius >= MIN_RADIUS && p.radius < MAX_RADIUS

    enum class Problem { OUT_OF_RING, TOO_CLOSE, ANGLE, UNEVEN_RADIUS, COUNT }

    /** 세 국가 스폰 지점이 규칙을 지키는지. 시드 탐색 도구와 운영자 명령이 같이 쓴다. */
    fun validate(points: List<XZ>): List<Problem> {
        val out = mutableListOf<Problem>()
        if (points.size != 3) return listOf(Problem.COUNT)
        if (points.any { !inRing(it) }) out += Problem.OUT_OF_RING
        for (i in 0 until 3) for (j in i + 1 until 3) {
            if (points[i].dist(points[j]) < MIN_PAIR_DISTANCE) out += Problem.TOO_CLOSE
            val d = abs(points[i].angleDeg - points[j].angleDeg).let { if (it > 180) 360 - it else it }
            if (d < MIN_ANGLE_GAP) out += Problem.ANGLE
        }
        val radii = points.map { it.radius }
        if (radii.max() - radii.min() > MAX_RADIUS_SPREAD) out += Problem.UNEVEN_RADIUS
        return out.distinct()
    }

    /** 시드 탐색용 후보: 반지름 r, 시작 각 θ 에서 120° 간격 세 점. */
    fun triangle(radius: Double, startDeg: Double): List<XZ> = (0 until 3).map {
        val a = Math.toRadians(startDeg + 120.0 * it)
        XZ(radius * cos(a), radius * sin(a))
    }
}

object PersonalSpawn {
    const val MIN_OFFSET = 40.0
    const val MAX_OFFSET = 60.0
    const val TRIES = 48

    /** 고리(40~60) 안에서 넓이에 고르게 한 점. */
    fun sample(random: Random, center: XZ, min: Double = MIN_OFFSET, max: Double = MAX_OFFSET): XZ {
        val r = sqrt(random.nextDouble() * (max * max - min * min) + min * min)
        val a = random.nextDouble() * 2 * PI
        return XZ(center.x + r * cos(a), center.z + r * sin(a))
    }

    /**
     * 땅 위 안전한 자리를 고른다. [surfaceY] 는 (x, z) 의 서 있을 수 있는 높이를 돌려주거나,
     * 물·용암·나뭇잎 위·선인장 옆·다른 국가 영토처럼 안 되는 자리면 null 을 돌려준다(모드가 월드를 보고 판정).
     * [TRIES] 번 안에 못 찾으면 국가 스폰 지점 자체([fallback])를 쓴다.
     */
    fun pick(random: Random, center: XZ, fallback: XYZ, surfaceY: (Int, Int) -> Int?): XYZ {
        repeat(TRIES) {
            val p = sample(random, center)
            val x = kotlin.math.floor(p.x).toInt()
            val z = kotlin.math.floor(p.z).toInt()
            val y = surfaceY(x, z)
            if (y != null) return XYZ(x, y, z)
        }
        return fallback
    }
}
