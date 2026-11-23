package dev.menonng.snw.logic.discord

import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 국가 추첨식(docs/discord/peer-rating.md 5절). 같은 입력·같은 시드면 누구나 같은 결과를 재현한다(난수는 SplitMix64 로 정해 둔다).
 * - 전력 p = 0.5·z(별점) + 0.5·z(참여도), 참여도 a = 0.5·z(예상 시간) + 0.5·z(열정).
 * - 팀장 3명을 국가에 하나씩, 서로 지목한 단짝은 한 단위. 국가를 돌며 가중 추첨(평균에서 멀어진 국가를 되돌리는 단위가 뽑히기 쉽다, 바닥 0.08).
 * - 재추첨 가드(최대 500번): 핵심 유저 수 차 ≤ 1, 예상 시간 합 (최대−최소)/평균 < 18%, 평균 별점 차 ≤ 0.35, 인원 차 ≤ 1.
 */
object TeamDraw {
    const val FLOOR = 0.08
    const val MAX_TRIES = 500
    const val HOURS_SPREAD = 0.18
    const val STAR_GAP = 0.35

    data class Player(val id: String, val stars: Double, val hours: Double, val passion: Double)
    data class Input(val players: List<Player>, val leaders: List<String>, val buddies: List<Pair<String, String>>, val nations: Int = 3)
    data class Result(val teams: List<List<String>>, val order: List<Pair<Int, List<String>>>, val tries: Int, val passed: Boolean, val metrics: Metrics)
    data class Metrics(val coreGap: Int, val hoursSpread: Double, val starGap: Double, val sizeGap: Int, val brokenBuddies: Int)

    /** 결정적 난수(SplitMix64). 다른 언어로도 같은 수열을 낼 수 있게 직접 구현한다. */
    class Rng(seed: Long) {
        private var s = seed
        fun nextLong(): Long { s += -0x61c8864680b583ebL; var z = s; z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L; z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L; return z xor (z ushr 31) }
        fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))
        fun nextInt(n: Int): Int = (nextDouble() * n).toInt().coerceAtMost(n - 1)
        fun <T> shuffle(xs: MutableList<T>) { for (i in xs.size - 1 downTo 1) { val j = nextInt(i + 1); val t = xs[i]; xs[i] = xs[j]; xs[j] = t } }
    }

    fun seedHash(seed: String): String = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray()).joinToString("") { "%02x".format(it) }
    fun seedLong(seed: String): Long = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray()).take(8).fold(0L) { a, b -> (a shl 8) or (b.toLong() and 0xff) }

    private fun z(xs: List<Double>): List<Double> {
        val m = xs.average(); val sd = sqrt(xs.map { (it - m) * (it - m) }.average()).let { if (it < 1e-9) 1.0 else it }
        return xs.map { (it - m) / sd }
    }

    data class Prepared(val ids: List<String>, val power: Map<String, Double>, val hours: Map<String, Double>, val stars: Map<String, Double>, val core: Set<String>, val mutual: List<Pair<String, String>>)

    fun prepare(inp: Input): Prepared {
        val ps = inp.players
        val zh = z(ps.map { it.hours }); val zp = z(ps.map { it.passion })
        val a = ps.indices.map { 0.5 * zh[it] + 0.5 * zp[it] }
        val zs = z(ps.map { it.stars }); val za = z(a)
        val power = ps.indices.associate { ps[it].id to (0.5 * zs[it] + 0.5 * za[it]) }
        val coreN = max(inp.nations, ps.size / 2)          // 핵심 유저: 예상 시간 상위(활성 인구 = 절반으로 본다)
        val core = ps.sortedByDescending { it.hours }.take(coreN).map { it.id }.toSet()
        val ids = ps.map { it.id }.toSet()
        val mutual = inp.buddies.filter { (x, y) -> x in ids && y in ids && x != y && inp.buddies.any { it.first == y && it.second == x } }
            .map { if (it.first < it.second) it else it.second to it.first }.distinct()
        return Prepared(ps.map { it.id }, power, ps.associate { it.id to it.hours }, ps.associate { it.id to it.stars }, core, mutual)
    }

    fun metrics(teams: List<List<String>>, p: Prepared): Metrics {
        val cores = teams.map { t -> t.count { it in p.core } }
        val hrs = teams.map { t -> t.sumOf { p.hours[it] ?: 0.0 } }
        val stars = teams.map { t -> if (t.isEmpty()) 0.0 else t.map { p.stars[it] ?: 0.0 }.average() }
        val sizes = teams.map { it.size }
        val where = teams.flatMapIndexed { i, t -> t.map { it to i } }.toMap()
        val broken = p.mutual.count { (x, y) -> where[x] != where[y] }
        val meanH = hrs.average().let { if (it <= 0) 1.0 else it }
        return Metrics(cores.max() - cores.min(), (hrs.max() - hrs.min()) / meanH, stars.max() - stars.min(), sizes.max() - sizes.min(), broken)
    }

    fun passes(m: Metrics): Boolean = m.coreGap <= 1 && m.hoursSpread < HOURS_SPREAD && m.starGap <= STAR_GAP + 1e-9 && m.sizeGap <= 1

    private fun badness(m: Metrics): Double =
        max(0, m.coreGap - 1) * 3.0 + max(0.0, m.hoursSpread - HOURS_SPREAD) * 10 + max(0.0, m.starGap - STAR_GAP) * 5 + max(0, m.sizeGap - 1) * 10

    /** 한 번 뽑기. order = 공개 순서(국가, 단위 구성원). */
    private fun once(inp: Input, p: Prepared, rng: Rng): Pair<List<MutableList<String>>, List<Pair<Int, List<String>>>> {
        val n = inp.nations
        val N = p.ids.size
        val cap = (0 until n).map { i -> if (i < N % n) ceil(N / n.toDouble()).toInt() else floor(N / n.toDouble()).toInt() }.toMutableList()
        rng.shuffle(cap)
        val order = (0 until n).toMutableList(); rng.shuffle(order)
        val teams = List(n) { mutableListOf<String>() }
        val reveal = ArrayList<Pair<Int, List<String>>>()
        val placed = HashSet<String>()
        val buddyOf = HashMap<String, String>().apply { p.mutual.forEach { (x, y) -> put(x, y); put(y, x) } }
        inp.leaders.take(n).forEachIndexed { k, l ->
            val t = order[k]
            val unit = listOfNotNull(l, buddyOf[l]?.takeIf { it !in placed && it !in inp.leaders })
            teams[t] += unit; placed += unit; reveal += t to unit
        }
        val units = ArrayList<List<String>>()
        for (id in p.ids) if (id !in placed) {
            val b = buddyOf[id]
            if (b != null && b !in placed && units.none { b in it }) units += listOf(id, b).also { placed += it } else if (units.none { id in it }) { units += listOf(id); placed += id }
        }
        val all = p.power.values.toList(); val mu = all.average(); val sigma = sqrt(all.map { (it - mu) * (it - mu) }.average()).let { if (it < 1e-9) 1.0 else it }
        var guard = 0
        while (units.isNotEmpty() && guard++ < 10_000) {
            var progressed = false
            for (t in order) {
                val room = cap[t] - teams[t].size
                if (room <= 0 || units.isEmpty()) continue
                var cands = units.filter { it.size <= room }
                if (cands.isEmpty()) {
                    // 묶음이 들어갈 자리가 없으면 묶음을 풀어 한 명씩
                    val big = units.first { it.size > room }
                    units.remove(big); big.forEach { units += listOf(it) }
                    cands = units.filter { it.size <= room }
                }
                val m = if (teams[t].isEmpty()) mu else teams[t].map { p.power[it]!! }.average()
                val w = cands.map { u ->
                    val m2 = (teams[t].sumOf { p.power[it]!! } + u.sumOf { p.power[it]!! }) / (teams[t].size + u.size)
                    FLOOR + max(0.0, abs(m - mu) - abs(m2 - mu)) / sigma * teams[t].size
                }
                var x = rng.nextDouble() * w.sum(); var pick = cands.last()
                for (i in cands.indices) { x -= w[i]; if (x <= 0) { pick = cands[i]; break } }
                units.remove(pick); teams[t] += pick; reveal += t to pick; progressed = true
            }
            if (!progressed) break
        }
        return teams to reveal
    }

    fun draw(inp: Input, seed: String): Result {
        val p = prepare(inp)
        val rng = Rng(seedLong(seed))
        var best: Triple<List<List<String>>, List<Pair<Int, List<String>>>, Metrics>? = null
        var bestBad = Double.MAX_VALUE
        for (k in 1..MAX_TRIES) {
            val (teams, order) = once(inp, p, rng)
            val m = metrics(teams, p)
            if (passes(m)) return Result(teams, order, k, true, m)
            val b = badness(m)
            if (b < bestBad) { bestBad = b; best = Triple(teams, order, m) }
        }
        return Result(best!!.first, best.second, MAX_TRIES, false, best.third)
    }

    /** 맞교환(교환 창): 두 사람을 바꿔도 가드를 만족하면 바꾼 결과, 아니면 null. */
    fun swap(teams: List<List<String>>, a: String, b: String, p: Prepared): List<List<String>>? {
        val ia = teams.indexOfFirst { a in it }; val ib = teams.indexOfFirst { b in it }
        if (ia < 0 || ib < 0 || ia == ib) return null
        val next = teams.mapIndexed { i, t -> when (i) { ia -> t.map { if (it == a) b else it }; ib -> t.map { if (it == b) a else it }; else -> t } }
        return if (passes(metrics(next, p))) next else null
    }

    /** 예비: 최적화 편성(담금질). 목적함수 J 가 작은 서로 다른 상위 [k] 안. */
    fun optimize(inp: Input, seed: String, k: Int = 3, restarts: Int = 12, steps: Int = 4000): List<Pair<Double, List<List<String>>>> {
        val p = prepare(inp)
        val rng = Rng(seedLong(seed))
        fun cost(teams: List<List<String>>): Double {
            val a = teams.map { t -> t.sumOf { p.hours[it] ?: 0.0 } }; val ma = a.average().let { if (it <= 0) 1.0 else it }
            val s = teams.map { t -> t.map { p.stars[it] ?: 0.0 }.average() }
            fun v(xs: List<Double>) = xs.average().let { m -> xs.map { (it - m) * (it - m) }.average() }
            val cores = teams.map { t -> t.count { it in p.core } }
            val m = metrics(teams, p)
            return 10 * v(a.map { it / ma }) + 4 * v(s) + 10 * cores.count { it == 0 } + (if (m.coreGap >= 2) 2 else 0) + 100 * m.brokenBuddies + (if (m.sizeGap > 1) 100 else 0)
        }
        val found = ArrayList<Pair<Double, List<List<String>>>>()
        repeat(restarts) {
            var cur = once(inp, p, rng).first.map { it.toMutableList() }
            var c = cost(cur)
            for (st in 0 until steps) {
                val temp = 1.0 * exp(-6.0 * st / steps)
                val i = rng.nextInt(cur.size); var j = rng.nextInt(cur.size); if (j == i) j = (j + 1) % cur.size
                if (cur[i].isEmpty() || cur[j].isEmpty()) continue
                val x = rng.nextInt(cur[i].size); val y = rng.nextInt(cur[j].size)
                val nx = cur.map { it.toMutableList() }
                val t = nx[i][x]; nx[i][x] = nx[j][y]; nx[j][y] = t
                val c2 = cost(nx)
                if (c2 < c || rng.nextDouble() < exp((c - c2) / max(1e-6, temp))) { cur = nx; c = c2 }
            }
            val key = cur.map { it.sorted() }.sortedBy { it.firstOrNull() }
            if (found.none { f -> f.second.map { it.sorted() }.sortedBy { it.firstOrNull() } == key }) found += c to cur
        }
        return found.sortedBy { it.first }.take(k)
    }

    /** 가입 때 고른 주간 예상 시간 구간 → 시간(가운데 값). */
    fun hoursOfBand(band: String?): Double = when (band?.replace(" ", "")) {
        "~3h", "3h이하" -> 2.0
        "3~7h" -> 5.0
        "7~14h" -> 10.0
        "14h~", "14h이상" -> 18.0
        else -> band?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull() ?: 5.0
    }
}
