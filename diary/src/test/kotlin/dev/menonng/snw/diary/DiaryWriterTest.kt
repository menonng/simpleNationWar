package dev.menonng.snw.diary

import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiaryWriterTest {
    private val noeul = PersonRef("p1", "노을", "1국", funnyOptIn = true)
    private val haneul = PersonRef("p2", "하늘", "2국")
    private val minjun = PersonRef("p3", "민준", "2국")
    private val shy = PersonRef("p4", "비밀이", "3국", showName = false)
    private val leaky = PersonRef("p5", "김민수2009", "3국")
    private val rain = PersonRef("p6", "빗소리", "1국")

    private fun day(n: Int, events: List<DiaryEvent>, before: Double, after: Double, scores: Map<String, Double> = emptyMap(), shards: Int? = null, moons: Int = 0) =
        DayInput(LocalDate.of(2027, 1, 15).plusDays(n.toLong()), n, events, DaySummary(before, after, scores, shards, moons))

    private val season = listOf(
        day(1, listOf(
            DiaryEvent.ZombieDeath(DayPart.NIGHT, noeul, "러너", Zone.Wilderness("북쪽")),
            DiaryEvent.RevenantRecovered(DayPart.NIGHT, noeul, noeul),
            DiaryEvent.Advancement(DayPart.DAY, haneul, "첫 번째 밤", rare = false),
            DiaryEvent.Advancement(DayPart.DAY, rain, "나무꾼", rare = false),
        ), 0.0, 0.3, mapOf("1국" to 40.0, "2국" to 35.0, "3국" to 12.0), moons = 2),
        day(2, listOf(
            DiaryEvent.PvpKill(DayPart.EVENING, minjun, haneul, emptyList(), Zone.Wilderness("동쪽")),
            DiaryEvent.AccidentDeath(DayPart.DAY, noeul, Accident.FALL, Zone.Territory("1국")),
            DiaryEvent.AccidentDeath(DayPart.EVENING, noeul, Accident.FALL, Zone.Territory("1국")),
            DiaryEvent.ZombieDeath(DayPart.DAWN, shy, "궁수 좀비", Zone.Territory("3국")),
            DiaryEvent.ZombieDeath(DayPart.DAWN, leaky, "탱커", Zone.Territory("3국")),
        ), 0.3, 0.6, mapOf("1국" to 50.0, "2국" to 61.0, "3국" to 20.0)),
        day(3, listOf(
            DiaryEvent.PvpKill(DayPart.NIGHT, haneul, minjun, listOf(rain), Zone.Landmark("접경 거점(1-2)")),
            DiaryEvent.AlphaKilled(DayPart.NIGHT, listOf(noeul, rain, shy), Zone.Wilderness("서쪽")),
            DiaryEvent.Starforce(DayPart.DAY, haneul, "다이아몬드 검", 9),
            DiaryEvent.VaccineShard(DayPart.NIGHT, rain),
            DiaryEvent.RevenantLooted(DayPart.NIGHT, minjun, haneul),
        ), 0.6, 0.55, mapOf("1국" to 80.0, "2국" to 70.0, "3국" to 33.0), shards = 1),
        day(4, emptyList(), 0.55, 0.6),
        day(5, listOf(
            DiaryEvent.BloodMoon(DayPart.NIGHT, defended = true, participants = listOf(noeul, haneul, shy, rain), target = "생존자 캠프"),
            DiaryEvent.NestDestroyed(DayPart.DAY, listOf(minjun, leaky), Zone.Wilderness("남쪽")),
            DiaryEvent.LandmarkCaptured(DayPart.EVENING, "2국", "접경 거점(2-3)", null),
        ), 0.6, 1.1, mapOf("1국" to 90.0, "2국" to 120.0, "3국" to 60.0), moons = 1),
    )

    private fun run(voice: Voice): List<DiaryWriter.Output> {
        val writer = DiaryWriter(voice, salt = "test-server")
        var memory = DiaryMemory()
        return season.map { input -> writer.write(input, memory).also { memory = it.memory } }
    }

    @Test
    @DisplayName("같은 입력이면 같은 글")
    fun case01() {
        assertEquals(run(Voice.SURVIVOR).map { it.diary.text() }, run(Voice.SURVIVOR).map { it.diary.text() })
    }

    @Test
    @DisplayName("상투어 없음, 목소리 일관, 분량 제한")
    fun case02() {
        for (voice in Voice.entries) for (out in run(voice)) {
            val text = out.diary.text()
            for (b in StyleLinter.banned) assertFalse(text.contains(b), "$voice 에 상투어 '$b'\n$text")
            val sentences = out.diary.paragraphs.flatMap { StyleLinter.splitSentences(it) }
            if (voice == Voice.SURVIVOR) assertTrue(sentences.none { it.endsWith("요.") }, text)
            if (voice == Voice.OBSERVER) assertTrue(sentences.none { it.endsWith("다.") }, text)
            assertTrue(text.length <= 1500)
            assertTrue(out.problems.isEmpty(), "$voice 문제: ${out.problems}\n$text")
        }
    }

    @Test
    @DisplayName("동의하지 않은 사람과 개인정보가 든 닉네임은 이름이 나오지 않는다")
    fun case03() {
        for (voice in Voice.entries) {
            val all = run(voice).joinToString("\n") { it.diary.text() }
            assertFalse(all.contains("비밀이"), all)
            assertFalse(all.contains("김민수2009"), all)
            assertTrue(all.contains("생존자"), all)
        }
    }

    @Test
    @DisplayName("반복 사고는 웃긴 기록에 동의한 사람만, 되갚기는 이야기로")
    fun case04() {
        val texts = run(Voice.SURVIVOR).map { it.diary.text() }
        assertTrue(texts[1].contains("또") || texts[1].contains("번째"), texts[1])
        assertTrue(texts[2].contains("되갚") || texts[2].contains("잊지 않았다") || texts[2].contains("빚"), texts[2])
        assertTrue(texts[4].contains("첫 블러드문"), texts[4])
    }

    @Test
    @DisplayName("일지 샘플을 파일로 남긴다(사람이 읽고 판단하는 용도)")
    fun case05() {
        val sb = StringBuilder("# 생존일지 샘플 (테스트 입력)\n\n")
        for (voice in Voice.entries) {
            sb.append("## ").append(if (voice == Voice.SURVIVOR) "생존자 목소리" else "관찰자 목소리").append("\n\n")
            for (out in run(voice)) sb.append("```\n").append(out.diary.text()).append("\n```\n\n")
        }
        File("build/diary-samples.md").apply { parentFile.mkdirs() }.writeText(sb.toString())
    }
}
