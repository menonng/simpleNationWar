package dev.menonng.snw.logic.territory

/** 블록 좌표 꼭짓점. 지도 모드에 넘길 때 x, z 그대로 쓴다. */
data class Point(val x: Int, val z: Int)

data class Ring(val points: List<Point>) {
    /** 부호 있는 넓이의 두 배. 양수면 반시계(바깥 경계), 음수면 시계(구멍). */
    fun signedArea2(): Long {
        var s = 0L
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            s += a.x.toLong() * b.z - b.x.toLong() * a.z
        }
        return s
    }

    /** 반직선 교차로 점이 고리 안에 있는지 본다. 경계 위의 점은 넘기지 않는다고 가정한다. */
    fun contains(px: Double, pz: Double): Boolean {
        var inside = false
        var j = points.size - 1
        for (i in points.indices) {
            val a = points[i]
            val b = points[j]
            if ((a.z > pz) != (b.z > pz)) {
                val xCross = (b.x - a.x) * (pz - a.z) / (b.z - a.z).toDouble() + a.x
                if (px < xCross) inside = !inside
            }
            j = i
        }
        return inside
    }
}

data class Polygon(val outer: Ring, val holes: List<Ring>)

/**
 * 청크 집합을 다각형(구멍 포함)으로 합친다. docs/design/nation.md '지도 연동' 3절.
 * 1) 청크마다 반시계 방향 변 4개 2) 이웃과 겹치는 반대 방향 변 제거 3) 남은 변을 이어 고리
 * 4) 넓이 부호로 바깥/구멍 구분 5) 일직선 위 꼭짓점 제거.
 * 대각선으로만 닿은 두 청크는 서로 다른 다각형이 된다(꼭짓점에서 왼쪽으로 꺾는 규칙).
 */
object PolygonMerger {
    private data class Edge(val from: Point, val to: Point)

    fun merge(chunks: Set<Chunk>, chunkSize: Int = 16): List<Polygon> {
        if (chunks.isEmpty()) return emptyList()
        val edges = HashSet<Edge>()
        for (c in chunks) {
            val x0 = c.x * chunkSize
            val z0 = c.z * chunkSize
            val x1 = x0 + chunkSize
            val z1 = z0 + chunkSize
            val square = listOf(Point(x0, z0), Point(x1, z0), Point(x1, z1), Point(x0, z1))
            for (i in 0 until 4) {
                val e = Edge(square[i], square[(i + 1) % 4])
                val reverse = Edge(e.to, e.from)
                if (!edges.remove(reverse)) edges.add(e)
            }
        }

        val outgoing = HashMap<Point, MutableList<Edge>>()
        for (e in edges) outgoing.getOrPut(e.from) { mutableListOf() }.add(e)

        val rings = mutableListOf<Ring>()
        val used = HashSet<Edge>()
        for (start in edges.sortedWith(compareBy({ it.from.z }, { it.from.x }, { it.to.z }, { it.to.x }))) {
            if (start in used) continue
            val pts = mutableListOf<Point>()
            var e = start
            while (true) {
                used += e
                pts += e.from
                if (e.to == start.from) break
                val dir = direction(e)
                e = outgoing.getValue(e.to).filter { it !in used }
                    .minByOrNull { turnRank(dir, direction(it)) } ?: break
            }
            rings += Ring(simplify(pts))
        }

        val outers = rings.filter { it.signedArea2() > 0 }
        val holes = rings.filter { it.signedArea2() < 0 }
        val holesOf = outers.associateWith { mutableListOf<Ring>() }
        for (h in holes) {
            val a = h.points[0]
            val b = h.points[1]
            val mx = (a.x + b.x) / 2.0
            val mz = (a.z + b.z) / 2.0
            val owner = outers.filter { it.contains(mx, mz) }.minByOrNull { it.signedArea2() } ?: continue
            holesOf.getValue(owner) += h
        }
        return outers.map { Polygon(it, holesOf.getValue(it)) }
    }

    private fun direction(e: Edge) = Point(Integer.signum(e.to.x - e.from.x), Integer.signum(e.to.z - e.from.z))

    /**
     * 왼쪽 0, 직진 1, 오른쪽 2, 되돌아가기 3. (x 오른쪽, z 위쪽인 수학 좌표 기준)
     * 바깥 경계는 반시계로 돌므로, 대각선으로 맞닿은 꼭짓점에서 왼쪽으로 꺾어야 각 덩어리가 따로 닫힌다.
     */
    private fun turnRank(d: Point, n: Point): Int {
        val right = Point(d.z, -d.x)
        val left = Point(-d.z, d.x)
        return when (n) {
            left -> 0
            d -> 1
            right -> 2
            else -> 3
        }
    }

    private fun simplify(pts: List<Point>): List<Point> {
        if (pts.size <= 3) return pts
        val out = mutableListOf<Point>()
        for (i in pts.indices) {
            val prev = pts[(i - 1 + pts.size) % pts.size]
            val cur = pts[i]
            val next = pts[(i + 1) % pts.size]
            val cross = (cur.x - prev.x).toLong() * (next.z - cur.z) - (cur.z - prev.z).toLong() * (next.x - cur.x)
            if (cross != 0L) out += cur
        }
        return out
    }
}
