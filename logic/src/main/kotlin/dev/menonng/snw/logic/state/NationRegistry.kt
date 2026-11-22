package dev.menonng.snw.logic.state

import dev.menonng.snw.api.ChunkKey
import dev.menonng.snw.api.Relation
import dev.menonng.snw.api.Role
import dev.menonng.snw.logic.core.NationCore
import java.util.UUID

/**
 * 국가·국민·영토·관계·코어 상태. docs/design/nation.md 2·3·4·5·7절.
 * 마인크래프트 타입을 쓰지 않는다. 모드가 JSON 으로 저장하고([Snapshot]), 이벤트를 붙인다.
 */
class NationRegistry {
    data class CorePos(val dimension: String, val x: Int, val y: Int, val z: Int) {
        val chunk: ChunkKey get() = ChunkKey(dimension, x shr 4, z shr 4)
    }

    class Nation(
        val id: String,
        var name: String,
        val colorRgb: Int,
        var leader: UUID? = null,
        val officers: MutableSet<UUID> = linkedSetOf(),
        val members: MutableSet<UUID> = linkedSetOf(),
        var spawnX: Int? = null,
        var spawnZ: Int? = null,
        var core: CorePos? = null,
        var coreHp: Double = NationCore.MAX_HP,
        var coreLastHit: Long = 0L,
        var coreHpAtLastHit: Double = NationCore.MAX_HP,
        /** 최근에 코어를 친 다른 국가와 시각(멸망 이전 판정, 5-1-1). */
        val recentAttackers: MutableMap<String, Long> = linkedMapOf(),
        var fallen: Boolean = false,
        var capitalMoved: Boolean = false,
    ) {
        fun roleOf(p: UUID): Role? = when {
            p !in members -> null
            p == leader -> Role.LEADER
            p in officers -> Role.OFFICER
            else -> Role.CITIZEN
        }
    }

    /** 두 국가 사이 관계. 적대 선언은 [hostileAt] 에 발효, 적대를 풀면 [rehostileAfter] 까지 다시 적대 불가. */
    class RelationState(
        var relation: Relation = Relation.NEUTRAL,
        var hostileAt: Long? = null,
        var hostileBy: String? = null,
        var rehostileAfter: Long = 0L,
        var allyProposedBy: String? = null,
        var peaceProposedBy: String? = null,
    )

    val nations = LinkedHashMap<String, Nation>()
    private val memberOf = HashMap<UUID, String>()
    private val claims = HashMap<ChunkKey, String>()
    private val relations = HashMap<Pair<String, String>, RelationState>()

    private fun key(a: String, b: String) = if (a < b) a to b else b to a

    // ---- 국가 ----
    fun define(id: String, name: String, colorRgb: Int): Nation = nations.getOrPut(id) { Nation(id, name, colorRgb) }
    fun nation(id: String): Nation? = nations[id]
    fun alive(): List<Nation> = nations.values.filter { !it.fallen }
    fun nationOf(player: UUID): Nation? = memberOf[player]?.let { nations[it] }

    /** 소속 변경. [nationId] 가 null 이면 무소속. 원수·간부 직위는 국가를 옮기면 사라진다. */
    fun assign(player: UUID, nationId: String?, role: Role = Role.CITIZEN) {
        memberOf.remove(player)?.let { old ->
            nations[old]?.let { n -> n.members.remove(player); n.officers.remove(player); if (n.leader == player) n.leader = null }
        }
        if (nationId == null) return
        val n = requireNotNull(nations[nationId]) { "국가 없음: $nationId" }
        n.members += player; memberOf[player] = nationId
        when (role) {
            Role.LEADER -> { n.leader = player; n.officers.remove(player) }
            Role.OFFICER -> if (n.officers.size < MAX_OFFICERS) n.officers += player
            Role.CITIZEN -> {}
        }
    }

    fun sameNation(a: UUID, b: UUID): Boolean = memberOf[a] != null && memberOf[a] == memberOf[b]

    // ---- 영토 ----
    fun ownerOf(chunk: ChunkKey): Nation? = claims[chunk]?.let { nations[it] }
    fun territory(id: String): Set<ChunkKey> = claims.filterValues { it == id }.keys
    fun claim(id: String, chunk: ChunkKey): Boolean {
        if (claims.containsKey(chunk)) return false
        claims[chunk] = id; return true
    }
    fun unclaim(chunk: ChunkKey): String? = claims.remove(chunk)
    fun allClaims(): Map<ChunkKey, String> = claims

    // ---- 관계 ----
    fun relationState(a: String, b: String): RelationState = relations.getOrPut(key(a, b)) { RelationState() }

    /** [now] 기준 실제 관계(예고 중인 적대가 발효됐으면 적대로 바꾼다). */
    fun relation(a: String, b: String, now: Long): Relation {
        if (a == b) return Relation.ALLIED
        val r = relations[key(a, b)] ?: return Relation.NEUTRAL
        r.hostileAt?.let { if (now >= it) { r.relation = Relation.HOSTILE; r.hostileAt = null; r.hostileBy = null } }
        return r.relation
    }

    fun allied(a: UUID, b: UUID, now: Long = System.currentTimeMillis()): Boolean {
        val na = memberOf[a] ?: return false
        val nb = memberOf[b] ?: return false
        return na != nb && relation(na, nb, now) == Relation.ALLIED
    }

    fun hostile(a: String, b: String, now: Long): Boolean = a != b && relation(a, b, now) == Relation.HOSTILE

    enum class RelationResult(val message: String) {
        OK("처리되었습니다."),
        PROPOSED("제안을 보냈습니다. 상대 원수가 수락하면 성립합니다."),
        SCHEDULED("적대를 선언했습니다. 12시간 뒤 발효됩니다."),
        ALREADY("이미 그 관계입니다."),
        ALLY_LIMIT("동맹은 국가당 하나까지입니다."),
        REHOSTILE_COOLDOWN("적대를 푼 뒤 72시간 동안은 다시 적대할 수 없습니다."),
        SELF("자기 국가와는 관계를 바꿀 수 없습니다."),
        NEED_PEACE("적대 중인 국가와는 먼저 적대를 풀어야 동맹을 맺을 수 있습니다."),
    }

    /** 원수가 관계를 바꾸려 할 때. 악화(동맹 파기·적대 선언)는 한쪽 결정, 개선(동맹·적대 해제)은 양쪽 동의(7절). */
    fun request(from: String, to: String, want: Relation, now: Long): RelationResult {
        if (from == to) return RelationResult.SELF
        val r = relationState(from, to)
        val cur = relation(from, to, now)
        if (cur == want && r.hostileAt == null) return RelationResult.ALREADY
        return when (want) {
            Relation.HOSTILE -> {
                if (now < r.rehostileAfter) return RelationResult.REHOSTILE_COOLDOWN
                if (r.hostileAt != null) return RelationResult.ALREADY
                r.relation = Relation.NEUTRAL; r.allyProposedBy = null; r.peaceProposedBy = null
                r.hostileAt = now + HOSTILE_DELAY_MILLIS; r.hostileBy = from
                RelationResult.SCHEDULED
            }
            Relation.ALLIED -> {
                if (cur == Relation.HOSTILE || r.hostileAt != null) return RelationResult.NEED_PEACE
                if (allyOf(from) != null || allyOf(to) != null) return RelationResult.ALLY_LIMIT
                if (r.allyProposedBy == to) { r.relation = Relation.ALLIED; r.allyProposedBy = null; r.hostileAt = null; RelationResult.OK }
                else { r.allyProposedBy = from; RelationResult.PROPOSED }
            }
            Relation.NEUTRAL -> when (cur) {
                Relation.ALLIED -> { r.relation = Relation.NEUTRAL; RelationResult.OK }       // 동맹 파기: 한쪽
                Relation.HOSTILE -> {
                    if (r.peaceProposedBy == to) { r.relation = Relation.NEUTRAL; r.peaceProposedBy = null; r.rehostileAfter = now + REHOSTILE_COOLDOWN_MILLIS; RelationResult.OK }
                    else { r.peaceProposedBy = from; RelationResult.PROPOSED }
                }
                Relation.NEUTRAL -> if (r.hostileAt != null && r.hostileBy == from) { r.hostileAt = null; r.hostileBy = null; RelationResult.OK } else RelationResult.ALREADY
            }
        }
    }

    /** 운영자 강제 설정: 바로 그 관계로. */
    fun force(a: String, b: String, rel: Relation) {
        val r = relationState(a, b)
        r.relation = rel; r.hostileAt = null; r.hostileBy = null; r.allyProposedBy = null; r.peaceProposedBy = null
    }

    fun allyOf(id: String): String? = nations.keys.firstOrNull { it != id && relations[key(id, it)]?.relation == Relation.ALLIED }

    // ---- 코어 ----
    /** [now] 기준 회복을 반영한 코어 체력. */
    fun coreHp(n: Nation, now: Long): Double {
        if (n.coreLastHit == 0L) return n.coreHp
        return NationCore.hpAt(n.coreHpAtLastHit, n.coreLastHit, now).also { n.coreHp = it }
    }

    /** 코어 피해를 적용한다. 0 이 되면 true. [attackerNation] 은 플레이어가 친 경우 그 국가. */
    fun hitCore(n: Nation, amount: Double, attackerNation: String?, now: Long): Boolean {
        if (amount <= 0 || n.fallen) return false
        val hp = (coreHp(n, now) - amount).coerceAtLeast(0.0)
        n.coreHp = hp; n.coreHpAtLastHit = hp; n.coreLastHit = now
        if (attackerNation != null && attackerNation != n.id) n.recentAttackers[attackerNation] = now
        return hp <= 0.0
    }

    /**
     * 국가 멸망(5-1-1): 국민이 옮겨 갈 국가를 정한다. [lastHitNation] = 마지막 타격이 플레이어면 그 국가.
     * 몬스터 막타면 최근 1시간 안에 가장 최근에 친 국가, 없으면 null(국민마다 자동 배정).
     */
    fun successorOf(n: Nation, lastHitNation: String?, now: Long): String? {
        if (lastHitNation != null && nations[lastHitNation]?.fallen == false) return lastHitNation
        return n.recentAttackers.filter { now - it.value <= FALL_ATTACKER_WINDOW_MILLIS && nations[it.key]?.fallen == false }
            .maxByOrNull { it.value }?.key
    }

    /** 멸망 처리: 영토를 풀고 관계를 지우고, 국민 목록을 돌려준다(이전은 모드가 [assign] 으로). */
    fun fall(n: Nation): List<UUID> {
        n.fallen = true
        claims.entries.removeIf { it.value == n.id }
        relations.keys.filter { it.first == n.id || it.second == n.id }.forEach { relations.remove(it) }
        val people = n.members.toList()
        n.leader = null; n.officers.clear()
        return people
    }

    // ---- 저장 ----
    data class NationDto(
        val id: String, val name: String, val colorRgb: Int, val leader: String?, val officers: List<String>, val members: List<String>,
        val spawnX: Int?, val spawnZ: Int?, val core: CorePos?, val coreHp: Double, val coreLastHit: Long, val coreHpAtLastHit: Double,
        val recentAttackers: Map<String, Long>, val fallen: Boolean, val capitalMoved: Boolean,
    )
    data class ClaimDto(val dimension: String, val x: Int, val z: Int, val nation: String)
    data class RelationDto(val a: String, val b: String, val relation: Relation, val hostileAt: Long?, val hostileBy: String?, val rehostileAfter: Long,
                           val allyProposedBy: String?, val peaceProposedBy: String?)
    data class Snapshot(val nations: List<NationDto>, val claims: List<ClaimDto>, val relations: List<RelationDto>)

    fun snapshot(): Snapshot = Snapshot(
        nations.values.map { n ->
            NationDto(n.id, n.name, n.colorRgb, n.leader?.toString(), n.officers.map { it.toString() }, n.members.map { it.toString() },
                n.spawnX, n.spawnZ, n.core, n.coreHp, n.coreLastHit, n.coreHpAtLastHit, n.recentAttackers.toMap(), n.fallen, n.capitalMoved)
        },
        claims.map { (k, v) -> ClaimDto(k.dimension, k.x, k.z, v) },
        relations.map { (k, r) -> RelationDto(k.first, k.second, r.relation, r.hostileAt, r.hostileBy, r.rehostileAfter, r.allyProposedBy, r.peaceProposedBy) },
    )

    fun restore(s: Snapshot) {
        nations.clear(); memberOf.clear(); claims.clear(); relations.clear()
        for (d in s.nations) {
            val n = Nation(d.id, d.name, d.colorRgb, d.leader?.let(UUID::fromString), d.officers.map(UUID::fromString).toMutableSet(),
                d.members.map(UUID::fromString).toMutableSet(), d.spawnX, d.spawnZ, d.core, d.coreHp, d.coreLastHit, d.coreHpAtLastHit,
                (d.recentAttackers ?: emptyMap()).toMutableMap(), d.fallen, d.capitalMoved)
            nations[n.id] = n
            n.members.forEach { memberOf[it] = n.id }
        }
        for (c in s.claims) claims[ChunkKey(c.dimension, c.x, c.z)] = c.nation
        for (r in s.relations) relations[key(r.a, r.b)] = RelationState(r.relation, r.hostileAt, r.hostileBy, r.rehostileAfter, r.allyProposedBy, r.peaceProposedBy)
    }

    companion object {
        const val MAX_OFFICERS = 2
        const val HOSTILE_DELAY_MILLIS = 12L * 60 * 60 * 1000
        const val REHOSTILE_COOLDOWN_MILLIS = 72L * 60 * 60 * 1000
        const val FALL_ATTACKER_WINDOW_MILLIS = 60L * 60 * 1000

        /** 기본 세 국가(2절): 빨강·파랑·노랑. */
        val DEFAULTS = listOf(Triple("n1", "1국", 0xFF0045), Triple("n2", "2국", 0x3355BB), Triple("n3", "3국", 0xFFCC11))
    }
}
