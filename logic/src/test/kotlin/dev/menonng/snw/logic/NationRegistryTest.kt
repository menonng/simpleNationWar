package dev.menonng.snw.logic

import dev.menonng.snw.api.ChunkKey
import dev.menonng.snw.api.Relation
import dev.menonng.snw.api.Role
import dev.menonng.snw.logic.state.NationRegistry
import dev.menonng.snw.logic.state.NationRegistry.RelationResult
import org.junit.jupiter.api.DisplayName
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NationRegistryTest {
    private val h = 60L * 60 * 1000
    private fun reg() = NationRegistry().apply { NationRegistry.DEFAULTS.forEach { (id, n, c) -> define(id, n, c) } }
    private val a = UUID(0, 1); private val b = UUID(0, 2); private val c = UUID(0, 3)

    @Test
    @DisplayName("소속·직위: 국가를 옮기면 직위가 사라지고, 간부는 둘까지")
    fun case01() {
        val r = reg()
        r.assign(a, "n1", Role.LEADER); r.assign(b, "n1", Role.OFFICER)
        assertEquals(Role.LEADER, r.nation("n1")!!.roleOf(a)); assertTrue(r.sameNation(a, b))
        r.assign(a, "n2")
        assertNull(r.nation("n1")!!.leader); assertEquals(Role.CITIZEN, r.nation("n2")!!.roleOf(a)); assertFalse(r.sameNation(a, b))
        r.assign(a, null); assertNull(r.nationOf(a))
    }

    @Test
    @DisplayName("관계: 적대는 12시간 뒤 발효, 동맹은 양쪽 수락·국가당 하나, 적대 해제는 양쪽 동의 후 72시간 재적대 불가")
    fun case02() {
        val r = reg(); val t = 1_000_000L
        assertEquals(RelationResult.SCHEDULED, r.request("n1", "n2", Relation.HOSTILE, t))
        assertEquals(Relation.NEUTRAL, r.relation("n1", "n2", t + 11 * h))
        assertEquals(Relation.HOSTILE, r.relation("n1", "n2", t + 12 * h))
        assertEquals(RelationResult.NEED_PEACE, r.request("n1", "n2", Relation.ALLIED, t + 13 * h))
        assertEquals(RelationResult.PROPOSED, r.request("n1", "n2", Relation.NEUTRAL, t + 13 * h))
        assertEquals(RelationResult.OK, r.request("n2", "n1", Relation.NEUTRAL, t + 14 * h))
        assertEquals(RelationResult.REHOSTILE_COOLDOWN, r.request("n2", "n1", Relation.HOSTILE, t + 20 * h))
        assertEquals(RelationResult.PROPOSED, r.request("n1", "n3", Relation.ALLIED, t))
        assertEquals(RelationResult.OK, r.request("n3", "n1", Relation.ALLIED, t))
        assertEquals(RelationResult.ALLY_LIMIT, r.request("n2", "n3", Relation.ALLIED, t))
        r.assign(a, "n1"); r.assign(c, "n3"); assertTrue(r.allied(a, c, t))
        assertEquals(RelationResult.OK, r.request("n3", "n1", Relation.NEUTRAL, t))        // 동맹 파기는 한쪽
        // 예고 중인 적대는 선언한 쪽만 거둔다
        r.request("n2", "n3", Relation.HOSTILE, t)
        assertEquals(RelationResult.ALREADY, r.request("n3", "n2", Relation.NEUTRAL, t))
        assertEquals(RelationResult.OK, r.request("n2", "n3", Relation.NEUTRAL, t))
        assertEquals(Relation.NEUTRAL, r.relation("n2", "n3", t + 13 * h))
    }

    @Test
    @DisplayName("코어 피해·회복·멸망 이전 대상, 저장과 복원")
    fun case03() {
        val r = reg(); val n1 = r.nation("n1")!!; val t = 10 * h
        r.assign(a, "n1", Role.LEADER); r.assign(b, "n2")
        r.claim("n1", ChunkKey("minecraft:overworld", 0, 0)); r.claim("n1", ChunkKey("minecraft:overworld", 1, 0))
        assertFalse(r.claim("n2", ChunkKey("minecraft:overworld", 1, 0)))
        assertFalse(r.hitCore(n1, 2.0, "n2", t))
        assertEquals(99_998.0, r.coreHp(n1, t + h))
        assertEquals(100_000.0, r.coreHp(n1, t + 3 * h))           // 2시간 뒤부터 시간당 10
        assertEquals("n2", r.successorOf(n1, null, t + 30 * 60 * 1000))
        assertNull(r.successorOf(n1, null, t + 2 * h))
        assertEquals("n3", r.successorOf(n1, "n3", t))
        val snap = r.snapshot()
        val r2 = NationRegistry().apply { restore(snap) }
        assertEquals(Role.LEADER, r2.nation("n1")!!.roleOf(a)); assertEquals(2, r2.territory("n1").size)
        assertEquals(listOf(a), r2.fall(r2.nation("n1")!!)); assertTrue(r2.territory("n1").isEmpty())
    }
}
