package dev.menonng.snw.logic

import dev.menonng.snw.logic.kda.Allegiance
import dev.menonng.snw.logic.kda.DamageEntry
import dev.menonng.snw.logic.kda.DeathInput
import dev.menonng.snw.logic.kda.KdaJudge
import dev.menonng.snw.logic.kda.KdaStats
import dev.menonng.snw.logic.nickname.NicknameValidator
import dev.menonng.snw.logic.nickname.NicknameValidator.Reason
import dev.menonng.snw.logic.nickname.NicknameValidator.Verdict
import dev.menonng.snw.logic.territory.Chunk
import dev.menonng.snw.logic.territory.ClaimDenied
import dev.menonng.snw.logic.territory.PolygonMerger
import dev.menonng.snw.logic.territory.TerritoryRules
import dev.menonng.snw.logic.territory.UnclaimDenied
import dev.menonng.snw.logic.text.KoreanJosa
import java.util.UUID
import kotlin.test.Test
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KoreanJosaTest {
    @Test
    @DisplayName("받침 유무와 ㄹ 받침")
    fun case01() {
        assertEquals("철수가", KoreanJosa.iGa("철수"))
        assertEquals("민준이", KoreanJosa.iGa("민준"))
        assertEquals("하늘은", KoreanJosa.eunNeun("하늘"))
        assertEquals("하늘로", KoreanJosa.euroRo("하늘"))
        assertEquals("민준으로", KoreanJosa.euroRo("민준"))
        assertEquals("철수로", KoreanJosa.euroRo("철수"))
        assertEquals("영희와", KoreanJosa.gwaWa("영희"))
        assertEquals("민준과", KoreanJosa.gwaWa("민준"))
        assertEquals("민준이었다", KoreanJosa.copulaPast("민준"))
        assertEquals("철수였다", KoreanJosa.copulaPast("철수"))
    }

    @Test
    @DisplayName("숫자·영문으로 끝나는 이름")
    fun case02() {
        assertEquals("Steve가", KoreanJosa.iGa("Steve"))
        assertEquals("Sam이", KoreanJosa.iGa("Sam"))
        assertEquals("Paul은", KoreanJosa.eunNeun("Paul"))
        assertEquals("Paul로", KoreanJosa.euroRo("Paul"))
        assertEquals("zombie_7은", KoreanJosa.eunNeun("zombie_7"))
        assertEquals("노을3을", KoreanJosa.eulReul("노을3"))
        assertEquals("하루2를", KoreanJosa.eulReul("하루2"))
    }

    @Test
    @DisplayName("목록 잇기")
    fun case03() {
        assertEquals("철수", KoreanJosa.join(listOf("철수")))
        assertEquals("철수와 민준", KoreanJosa.join(listOf("철수", "민준")))
        assertEquals("철수, 민준과 영희", KoreanJosa.join(listOf("철수", "민준", "영희")))
    }
}

class NicknameValidatorTest {
    private val v = NicknameValidator(bannedWords = setOf("바보"))

    private fun verdict(s: String) = v.check(s).verdict
    private fun reasons(s: String) = v.check(s).reasons

    @Test
    @DisplayName("평범한 닉네임은 통과")
    fun case01() {
        for (n in listOf("노을빛", "좀비사냥꾼", "하루2", "Ghost_7", "철갑상어", "고양이", "생존중", "online", "도라지", "치킨최고", "그리고")) {
            assertEquals(Verdict.ACCEPT, verdict(n), "$n -> ${reasons(n)}")
        }
    }

    @Test
    @DisplayName("형식 위반은 거절")
    fun case02() {
        assertTrue(Reason.LENGTH in reasons("가"))
        assertTrue(Reason.LENGTH in reasons("가나다라마바사아자차카"))
        assertTrue(Reason.CHARSET in reasons("노을 빛"))
        assertTrue(Reason.CHARSET in reasons("별★"))
        assertTrue(Reason.BANNED in reasons("완전바보"))
        val ctx = NicknameValidator.Context(takenNicknames = setOf("노을빛"), otherMinecraftNames = setOf("Steve123"))
        assertTrue(Reason.DUPLICATE in v.check("노을빛", ctx).reasons)
        assertTrue(Reason.MC_NAME_CLASH in v.check("steve123", ctx).reasons)
    }

    @Test
    @DisplayName("연락처·긴 숫자·학교는 거절")
    fun case03() {
        assertEquals(Verdict.REJECT, verdict("민수0101"))
        assertTrue(Reason.LONG_DIGITS in reasons("노을2009"))
        assertTrue(Reason.TOO_MANY_DIGITS in reasons("a1b2c3"))
        assertTrue(Reason.CONTACT in reasons("insta_noeul"))
        assertTrue(Reason.CONTACT in reasons("카톡하자"))
        assertTrue(Reason.CONTACT in reasons("noeul_dm"))
        assertTrue(Reason.SCHOOL in reasons("한빛고등"))
        assertTrue(Reason.SCHOOL in reasons("3학년2반"))
        assertEquals(Verdict.REJECT, verdict("3학년"))
    }

    @Test
    @DisplayName("실명·출생연도·학교명·지역처럼 보이면 운영자 확인")
    fun case04() {
        assertEquals(Verdict.REVIEW, verdict("김민수"))
        assertTrue(Reason.REAL_NAME in reasons("박서연"))
        assertTrue(Reason.REAL_NAME in reasons("남궁민수"))
        assertTrue(Reason.REAL_NAME_ROMAN in reasons("kim_minsu"))
        assertTrue(Reason.REAL_NAME_ROMAN in reasons("minsukim"))
        assertTrue(Reason.REAL_NAME_ROMAN in reasons("parkjiwoo"))
        assertTrue(Reason.BIRTH_YEAR in reasons("노을08"))
        assertTrue(Reason.SCHOOL in reasons("한빛고"))
        assertEquals(Verdict.REVIEW, verdict("한빛고"))
        assertTrue(Reason.REGION in reasons("부산사람"))
        assertTrue(Reason.REGION in reasons("역삼1동"))
    }
}

class KdaJudgeTest {
    private val a = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val b = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val c = UUID.fromString("00000000-0000-0000-0000-00000000000c")
    private val d = UUID.fromString("00000000-0000-0000-0000-00000000000d")
    private val nation = mapOf(a to 1, b to 2, c to 1, d to 2)

    private fun judge() = KdaJudge(object : Allegiance {
        override fun sameNation(x: UUID, y: UUID) = nation[x] == nation[y]
        override fun allied(x: UUID, y: UUID) = false
    })

    @Test
    @DisplayName("어시스트 - 15초 안, 최소 피해, 같은 국가 제외")
    fun case01() {
        val now = 100_000L
        val out = judge().judge(
            DeathInput(
                victim = b, killer = a, victimMaxHealth = 20.0, timeMillis = now,
                damage = listOf(
                    DamageEntry(a, 10.0, now - 1000),
                    DamageEntry(c, 3.0, now - 14_900),
                    DamageEntry(d, 5.0, now - 2000),
                ),
            ),
        )
        assertTrue(out.killCounted)
        assertTrue(out.pvpDeath)
        assertEquals(listOf(c), out.assists, "c 는 어시스트, d 는 피해자와 같은 국가라 제외")
    }

    @Test
    @DisplayName("15초가 지났거나 피해가 작으면 어시스트가 아니다")
    fun case02() {
        val now = 100_000L
        val out = judge().judge(
            DeathInput(b, a, 20.0, listOf(DamageEntry(c, 5.0, now - 15_100), DamageEntry(c, 0.5, now - 1000)), now),
        )
        assertEquals(emptyList(), out.assists)
    }

    @Test
    @DisplayName("같은 국가 사살, 자살, 좀비 사망")
    fun case03() {
        val j = judge()
        val team = j.judge(DeathInput(c, a, 20.0, emptyList(), 0))
        assertTrue(team.teamKill && !team.killCounted)
        val self = j.judge(DeathInput(a, a, 20.0, emptyList(), 0))
        assertTrue(!self.killCounted && !self.pvpDeath)
        val pve = j.judge(DeathInput(a, null, 20.0, emptyList(), 0))
        assertTrue(!pve.killCounted && !pve.pvpDeath)
    }

    @Test
    @DisplayName("같은 상대를 10분 안에 세 번째 죽이면 K 를 세지 않는다")
    fun case04() {
        val j = judge()
        val r = (0 until 3).map { j.judge(DeathInput(b, a, 20.0, emptyList(), it * 60_000L)).killCounted }
        assertEquals(listOf(true, true, false), r)
        assertTrue(j.judge(DeathInput(b, a, 20.0, emptyList(), 11 * 60_000L)).killCounted)
    }

    @Test
    @DisplayName("KDA 계산과 표시")
    fun case05() {
        val s = KdaStats(kills = 12, deaths = 9, assists = 5, deathsPvp = 5)
        assertEquals("1.89", KdaStats.format(s.kda))
        assertEquals("3.40", KdaStats.format(s.kdaPvp))
        assertEquals("3.00", KdaStats.format(KdaStats(2, 0, 1, 0).kda))
    }
}

class TerritoryTest {
    private val rules = TerritoryRules()

    @Test
    @DisplayName("영토 상한 예시")
    fun case01() {
        assertEquals(54, rules.cap(5, 0.0, 0))
        assertEquals(79, rules.cap(5, 2500.0, 0))
        assertEquals(112, rules.cap(5, 10_000.0, 1))
    }

    @Test
    @DisplayName("점령 조건")
    fun case02() {
        val main = setOf(Chunk(0, 0), Chunk(1, 0))
        val foreign = setOf(Chunk(10, 0))
        assertNull(rules.checkClaim(Chunk(2, 0), main, emptyList(), foreign, emptySet(), 54))
        assertEquals(ClaimDenied.NOT_ADJACENT, rules.checkClaim(Chunk(3, 3), main, emptyList(), foreign, emptySet(), 54))
        assertEquals(ClaimDenied.TOO_CLOSE_TO_FOREIGN, rules.checkClaim(Chunk(2, 0), main, emptyList(), setOf(Chunk(4, 1)), emptySet(), 54))
        assertNull(rules.checkClaim(Chunk(2, 0), main, emptyList(), setOf(Chunk(5, 0)), emptySet(), 54))
        assertEquals(ClaimDenied.CAP_REACHED, rules.checkClaim(Chunk(2, 0), main, emptyList(), foreign, emptySet(), 2))
        assertEquals(ClaimDenied.FORBIDDEN_ZONE, rules.checkClaim(Chunk(2, 0), main, emptyList(), foreign, setOf(Chunk(2, 0)), 54))
        assertEquals(ClaimDenied.ALREADY_OWNED, rules.checkClaim(Chunk(10, 0), main, emptyList(), foreign, emptySet(), 54))
    }

    @Test
    @DisplayName("전초기지")
    fun case03() {
        val main = setOf(Chunk(0, 0))
        val outpost = setOf(Chunk(50, 50), Chunk(51, 50), Chunk(52, 50), Chunk(53, 50))
        assertEquals(ClaimDenied.OUTPOST_TOO_BIG, rules.checkClaim(Chunk(54, 50), main, listOf(outpost), emptySet(), emptySet(), 99))
        assertNull(rules.checkClaim(Chunk(100, 100), main, listOf(outpost), emptySet(), emptySet(), 99, asNewOutpost = true))
        assertEquals(ClaimDenied.OUTPOST_LIMIT, rules.checkClaim(Chunk(100, 100), main, listOf(outpost, setOf(Chunk(70, 70))), emptySet(), emptySet(), 99, asNewOutpost = true))
        assertEquals(ClaimDenied.OUTPOST_TOO_CLOSE, rules.checkClaim(Chunk(100, 100), main, listOf(outpost), setOf(Chunk(105, 100)), emptySet(), 99, asNewOutpost = true))
    }

    @Test
    @DisplayName("해제하면 수도와 끊어지는 청크는 해제할 수 없다")
    fun case04() {
        val main = setOf(Chunk(0, 0), Chunk(1, 0), Chunk(2, 0))
        assertEquals(UnclaimDenied.SPLITS_TERRITORY, rules.checkUnclaim(Chunk(1, 0), Chunk(0, 0), main, emptyList()))
        assertNull(rules.checkUnclaim(Chunk(2, 0), Chunk(0, 0), main, emptyList()))
        assertEquals(UnclaimDenied.CAPITAL, rules.checkUnclaim(Chunk(0, 0), Chunk(0, 0), main, emptyList()))
    }
}

class PolygonMergerTest {
    @Test
    @DisplayName("한 청크, 일자, L자")
    fun case01() {
        assertEquals(4, PolygonMerger.merge(setOf(Chunk(0, 0))).single().outer.points.size)
        assertEquals(4, PolygonMerger.merge(setOf(Chunk(0, 0), Chunk(1, 0), Chunk(2, 0))).single().outer.points.size)
        val l = PolygonMerger.merge(setOf(Chunk(0, 0), Chunk(1, 0), Chunk(0, 1))).single()
        assertEquals(6, l.outer.points.size)
        assertEquals(3L * 256 * 2, l.outer.signedArea2())
    }

    @Test
    @DisplayName("가운데가 빈 3x3 은 구멍 하나")
    fun case02() {
        val ring = (0..2).flatMap { x -> (0..2).map { z -> Chunk(x, z) } }.filter { it != Chunk(1, 1) }.toSet()
        val p = PolygonMerger.merge(ring).single()
        assertEquals(4, p.outer.points.size)
        assertEquals(1, p.holes.size)
        assertEquals(-256L * 2, p.holes.single().signedArea2())
    }

    @Test
    @DisplayName("대각선으로만 닿거나 떨어진 청크는 다각형이 따로")
    fun case03() {
        assertEquals(2, PolygonMerger.merge(setOf(Chunk(0, 0), Chunk(1, 1))).size)
        assertEquals(2, PolygonMerger.merge(setOf(Chunk(0, 0), Chunk(5, 5))).size)
    }

    @Test
    @DisplayName("넓이 보존 - 무작위 모양")
    fun case04() {
        val rnd = kotlin.random.Random(9)
        repeat(30) {
            val set = (0 until 40).map { Chunk(rnd.nextInt(8), rnd.nextInt(8)) }.toSet()
            val polys = PolygonMerger.merge(set)
            val area = polys.sumOf { p -> p.outer.signedArea2() + p.holes.sumOf { it.signedArea2() } }
            assertEquals(set.size * 256L * 2, area)
        }
    }
}
