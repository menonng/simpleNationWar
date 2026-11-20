package dev.menonng.snw.logic

import dev.menonng.snw.logic.spawn.NationSpawn
import dev.menonng.snw.logic.spawn.PersonalSpawn
import dev.menonng.snw.logic.spawn.XYZ
import dev.menonng.snw.logic.spawn.XZ
import org.junit.jupiter.api.DisplayName
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpawnPointsTest {
    @Test
    @DisplayName("국가 스폰 지점은 1,000 이상 3,000 미만 고리")
    fun case01() {
        assertTrue(NationSpawn.inRing(XZ(1000.0, 0.0)))
        assertTrue(!NationSpawn.inRing(XZ(999.0, 0.0)))
        assertTrue(!NationSpawn.inRing(XZ(0.0, 3000.0)))
        assertTrue(NationSpawn.validate(NationSpawn.triangle(1500.0, 10.0)).isEmpty())
        assertTrue(NationSpawn.Problem.TOO_CLOSE in NationSpawn.validate(NationSpawn.triangle(800.0, 0.0)))
        assertTrue(NationSpawn.Problem.OUT_OF_RING in NationSpawn.validate(NationSpawn.triangle(800.0, 0.0)))
        val uneven = listOf(XZ(1100.0, 0.0), XZ(-1300.0, 2200.0), XZ(-1300.0, -2200.0))
        assertTrue(NationSpawn.Problem.UNEVEN_RADIUS in NationSpawn.validate(uneven))
        val bunched = listOf(XZ(2000.0, 0.0), XZ(1414.0, 1414.0), XZ(-2000.0, 0.0))
        assertTrue(NationSpawn.Problem.ANGLE in NationSpawn.validate(bunched))
    }

    @Test
    @DisplayName("개인 스폰은 국가 지점에서 40~60블록, 넓이에 고르게")
    fun case02() {
        val rng = Random(7)
        val c = XZ(1500.0, -300.0)
        var inner = 0
        repeat(20000) {
            val d = PersonalSpawn.sample(rng, c).dist(c)
            assertTrue(d in 40.0..60.0)
            if (d < 50.0) inner++
        }
        // 넓이 비례: 40~50 고리 넓이 / 40~60 고리 넓이 = 900/2000 = 0.45
        assertEquals(0.45, inner / 20000.0, 0.015)
    }

    @Test
    @DisplayName("안전한 땅을 찾고, 끝내 없으면 국가 지점으로")
    fun case03() {
        val c = XZ(0.0, 2000.0)
        val p = PersonalSpawn.pick(Random(3), c, XYZ(0, 70, 2000)) { x, _ -> if (x > 0) 64 else null }
        assertTrue(p.x > 0 && p.y == 64)
        assertTrue(hypotOk(p, c))
        val f = PersonalSpawn.pick(Random(3), c, XYZ(0, 70, 2000)) { _, _ -> null }
        assertEquals(XYZ(0, 70, 2000), f)
    }

    private fun hypotOk(p: XYZ, c: XZ): Boolean {
        val d = kotlin.math.hypot(p.x - c.x, p.z - c.z)
        return d in 38.0..62.0
    }
}
