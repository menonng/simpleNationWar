package dev.menonng.snw.logic.map

import kotlin.math.floor

/**
 * 휴대 지도(docs/design/handheld-map.md) 규칙. 마인크래프트 타입을 쓰지 않는다.
 * - 1블록 = 1칸, 128 × 128, 북쪽이 위. 중심은 나, 16블록 넘게 벗어나면 다시 그린다.
 * - 음영은 바닐라 지도 규칙(축척 0): 북쪽 칸과의 높이 차 + 격자 무늬 흔들림. 물은 깊이로.
 * - 국경: 청크 가장자리 칸에서 옆 청크 주인이 다르면 선. 우리 나라는 실선, 다른 나라는 점선.
 * - 감염 무늬: 4 × 4 청크 칸마다 레벨 색 점(4칸마다 한 점). Lv1~2 초록, Lv3~4 보라, Lv5 빨강.
 */
object HandheldMapRules {
    const val SIZE = 128
    const val HALF = 64
    const val RECENTER = 16
    const val ICON_TICKS = 5
    const val TERRAIN_REFRESH_TICKS = 100
    const val CHUNK_CACHE_TICKS = 600
    const val PATTERN_CELL_CHUNKS = 4
    const val PATTERN_STEP = 4

    /** 바닐라 밝기 단계: 0 LOW, 1 NORMAL, 2 HIGH (3 LOWEST 는 쓰지 않는다). */
    const val LOW = 0
    const val NORMAL = 1
    const val HIGH = 2

    fun needsRecenter(centerX: Int, centerZ: Int, x: Double, z: Double): Boolean =
        Math.abs(x - centerX) > RECENTER || Math.abs(z - centerZ) > RECENTER

    /** 바닐라 MapItem 의 땅 음영(축척 0). */
    fun landShade(h: Int, hNorth: Int, px: Int, pz: Int): Int {
        val d = (h - hNorth) * 4.0 / 4.0 + (((px + pz) and 1) - 0.5) * 0.4
        return when { d > 0.6 -> HIGH; d < -0.6 -> LOW; else -> NORMAL }
    }

    /** 바닐라 MapItem 의 물 음영(깊이). */
    fun waterShade(depth: Int, px: Int, pz: Int): Int {
        val d = depth * 0.1 + ((px + pz) and 1) * 0.2
        return when { d < 0.5 -> HIGH; d > 0.9 -> LOW; else -> NORMAL }
    }

    /** 지도 색 번호와 밝기 → 지도 칸 값(바닐라: id × 4 + 밝기). 0 은 빈 종이. */
    fun packed(colorId: Int, shade: Int): Byte = if (colorId == 0) 0 else (colorId * 4 + shade).toByte()

    /** 국가 색(RGB)에 가장 가까운 지도 색 번호. [palette] 는 번호 → RGB(0 번은 비어 있음). */
    fun nearestColor(rgb: Int, palette: List<Int>): Int {
        var best = 1; var bestD = Long.MAX_VALUE
        val r = rgb shr 16 and 255; val g = rgb shr 8 and 255; val b = rgb and 255
        for (i in 1 until palette.size) {
            val c = palette[i]; if (c == 0) continue
            val dr = (c shr 16 and 255) - r; val dg = (c shr 8 and 255) - g; val db = (c and 255) - b
            val d = 2L * dr * dr + 4L * dg * dg + 3L * db * db
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** 국경을 그릴 칸인가: 청크 안 위치(0~15)와 이웃 청크 주인. [own] 이 우리 나라면 실선, 아니면 2칸 간격 점선. */
    fun borderAt(lx: Int, lz: Int, owner: String?, west: String?, east: String?, north: String?, south: String?, px: Int, pz: Int, mine: Boolean): Boolean {
        if (owner == null) return false
        val edge = (lx == 0 && west != owner) || (lx == 15 && east != owner) || (lz == 0 && north != owner) || (lz == 15 && south != owner)
        if (!edge) return false
        return mine || ((px + pz) / 2) % 2 == 0
    }

    enum class Tint { NONE, GREEN, PURPLE, RED }
    fun patternTint(level: Int): Tint = when { level <= 0 -> Tint.NONE; level <= 2 -> Tint.GREEN; level <= 4 -> Tint.PURPLE; else -> Tint.RED }
    fun patternDot(px: Int, pz: Int): Boolean = px % PATTERN_STEP == 0 && pz % PATTERN_STEP == 0
    fun patternCell(chunk: Int): Int = Math.floorDiv(chunk, PATTERN_CELL_CHUNKS)

    /** 장식(아이콘) 좌표: 지도 중심에서의 블록 차 → 바닐라 바이트(−128~127, 2배). */
    fun iconCoord(delta: Double): Byte = (floor(delta * 2.0).toInt().coerceIn(-128, 127)).toByte()
    fun onMap(dx: Double, dz: Double): Boolean = dx >= -HALF && dx < HALF && dz >= -HALF && dz < HALF
    /** 바닐라 회전 값(0~15, 22.5도 단위). MapItemSavedData.addDecoration 과 같은 식. */
    fun iconRotation(yaw: Float): Byte {
        val y = ((yaw % 360f) + 540f) % 360f - 180f
        val d = if (y < 0) y - 8.0 else y + 8.0
        return ((d * 16.0 / 360.0).toInt() and 15).toByte()
    }
}
