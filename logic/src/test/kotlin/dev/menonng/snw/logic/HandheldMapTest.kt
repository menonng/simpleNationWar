package dev.menonng.snw.logic

import dev.menonng.snw.logic.map.HandheldMapRules as M
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HandheldMapTest {
    @Test fun recenterAfter16() {
        assertFalse(M.needsRecenter(100, 100, 115.0, 84.0))
        assertTrue(M.needsRecenter(100, 100, 117.0, 100.0))
    }

    @Test fun shadingFollowsVanilla() {
        assertEquals(M.HIGH, M.landShade(70, 68, 0, 0))
        assertEquals(M.LOW, M.landShade(66, 68, 0, 0))
        assertEquals(M.NORMAL, M.landShade(68, 68, 0, 1))
        assertEquals(M.HIGH, M.waterShade(1, 0, 0))
        assertEquals(M.LOW, M.waterShade(12, 0, 0))
        assertEquals(0.toByte(), M.packed(0, M.HIGH))
        assertEquals((7 * 4 + 2).toByte(), M.packed(7, M.HIGH))
    }

    @Test fun nearestNationColor() {
        val palette = listOf(0, 0xFF0000, 0x00FF00, 0x0000FF)
        assertEquals(1, M.nearestColor(0xFF0045, palette))
        assertEquals(3, M.nearestColor(0x3355BB, palette))
    }

    @Test fun bordersSolidForUsDashedForOthers() {
        assertTrue(M.borderAt(0, 5, "a", null, "a", "a", "a", 10, 3, mine = true))
        assertFalse(M.borderAt(5, 5, "a", null, "a", "a", "a", 10, 3, mine = true))     // 가장자리가 아님
        assertFalse(M.borderAt(0, 5, null, "a", null, null, null, 10, 3, mine = false))  // 주인 없는 청크
        val dashed = (0 until 8).count { M.borderAt(0, it, "b", null, "b", "b", "b", 0, it, mine = false) }
        assertTrue(dashed in 3..5, "점선 $dashed")
    }

    @Test fun patternAndIcons() {
        assertEquals(M.Tint.NONE, M.patternTint(0)); assertEquals(M.Tint.GREEN, M.patternTint(2))
        assertEquals(M.Tint.PURPLE, M.patternTint(4)); assertEquals(M.Tint.RED, M.patternTint(5))
        assertEquals(-1, M.patternCell(-1)); assertEquals(1, M.patternCell(4))
        assertEquals(127.toByte(), M.iconCoord(80.0)); assertEquals((-20).toByte(), M.iconCoord(-10.0))
        assertEquals(0.toByte(), M.iconRotation(0f))
        assertEquals(4.toByte(), M.iconRotation(90f))
        assertEquals(12.toByte(), M.iconRotation(-90f))
        assertEquals(12.toByte(), M.iconRotation(270f))
        assertTrue(M.onMap(-64.0, 63.9)); assertFalse(M.onMap(64.0, 0.0))
    }
}
