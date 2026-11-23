package dev.menonng.snw.logic

import dev.menonng.snw.logic.landmark.Capture
import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptureTest {
    private val ns = listOf("n1", "n2", "n3")
    private val none: (String) -> String? = { null }

    @Test
    @DisplayName("인원이 많으면 진행, 같으면 분쟁, 소유국이 같은 편이면 정지")
    fun case01() {
        assertEquals(Capture.Step("n1", 1.0), Capture.step(mapOf("n1" to 2, "n2" to 1), ns, none, owner = "n2"))
        assertTrue(Capture.step(mapOf("n1" to 1, "n2" to 1), ns, none, owner = null).contested)
        assertTrue(Capture.step(mapOf("n1" to 2), ns, none, owner = "n1").contested)            // 이미 자기 것
        assertTrue(Capture.step(emptyMap(), ns, none, owner = "n1").contested)
    }

    @Test
    @DisplayName("동맹은 인원에 함께 세고, 다른 둘이 동맹이면 고립 국가 ×1.25")
    fun case02() {
        val allied: (String) -> String? = { when (it) { "n1" -> "n2"; "n2" -> "n1"; else -> null } }
        // n1 1명 + 동맹 n2 1명 = 2 > n3 1명
        val s = Capture.step(mapOf("n1" to 1, "n2" to 1, "n3" to 1), ns, allied, owner = "n3")
        assertEquals(1.0, s.speed); assertTrue(s.leader == "n1" || s.leader == "n2")
        // 동맹 소유 거점은 같은 편이 못 뺏는다
        assertTrue(Capture.step(mapOf("n2" to 3), ns, allied, owner = "n1").contested)
        // 고립 국가 n3 이 앞서면 1.25
        assertEquals(Capture.Step("n3", 1.25), Capture.step(mapOf("n3" to 2), ns, allied, owner = "n1"))
    }
}
