package dev.menonng.snw.diary

import java.time.LocalDate

/**
 * 생존일지 입력 모델.
 *
 * 개인정보 보호를 위해 **모델 자체에 좌표·시각·채팅·마크 이름·디스코드 계정 칸이 없다.**
 * 시각은 하루를 다섯으로 나눈 [DayPart] 로만, 장소는 [Zone] 이름표로만 받는다.
 * 사람은 커스텀 닉네임과 표시 동의 여부만 가진 [PersonRef] 로 받는다.
 */
enum class DayPart(val label: String) {
    DAWN("새벽"), MORNING("아침"), DAY("낮"), EVENING("저녁"), NIGHT("밤");

    companion object {
        /** 봇이 사건 시각(0~23시)을 받으면 이것으로만 바꿔서 넘긴다. */
        fun ofHour(hour: Int): DayPart = when (hour) {
            in 0..5 -> DAWN
            in 6..10 -> MORNING
            in 11..16 -> DAY
            in 17..20 -> EVENING
            else -> NIGHT
        }
    }
}

/**
 * @param id 일지 안에서만 쓰는 불투명한 키(봇이 디스코드 ID 를 해시해서 만든다). 출력에 나오지 않는다.
 * @param showName 생존일지에 닉네임 표시를 동의했는가. 아니면 '한 생존자'로 나온다.
 * @param funnyOptIn '웃긴 기록'(같은 사고 반복 등)에 나오는 데 동의했는가.
 */
data class PersonRef(
    val id: String,
    val nickname: String,
    val nation: String,
    val showName: Boolean = true,
    val funnyOptIn: Boolean = false,
)

sealed interface Zone {
    data class Territory(val nation: String) : Zone
    data object Camp : Zone
    /** [direction] 은 '북쪽'처럼 월드 중심 기준 8방위 중 하나. 좌표는 받지 않는다. */
    data class Wilderness(val direction: String? = null) : Zone
    data class Landmark(val name: String) : Zone
    data object Nest : Zone
    data object Nether : Zone
    data object End : Zone
    data object Unknown : Zone
}

enum class Accident(val label: String) {
    FALL("낙사"), LAVA("용암"), DROWN("익사"), FIRE("불"), STARVE("굶주림"), EXPLOSION("폭발"), OTHER("사고")
}

sealed interface DiaryEvent {
    val part: DayPart

    data class ZombieDeath(override val part: DayPart, val victim: PersonRef, val variant: String?, val zone: Zone) : DiaryEvent
    data class AccidentDeath(override val part: DayPart, val victim: PersonRef, val cause: Accident, val zone: Zone) : DiaryEvent
    data class PvpKill(override val part: DayPart, val killer: PersonRef, val victim: PersonRef, val assists: List<PersonRef>, val zone: Zone) : DiaryEvent
    data class RevenantRecovered(override val part: DayPart, val owner: PersonRef, val by: PersonRef) : DiaryEvent
    data class RevenantLooted(override val part: DayPart, val owner: PersonRef, val by: PersonRef) : DiaryEvent
    data class AlphaKilled(override val part: DayPart, val killers: List<PersonRef>, val zone: Zone) : DiaryEvent
    data class NestDestroyed(override val part: DayPart, val participants: List<PersonRef>, val zone: Zone) : DiaryEvent
    data class BloodMoon(override val part: DayPart, val defended: Boolean, val participants: List<PersonRef>, val target: String) : DiaryEvent
    data class LandmarkCaptured(override val part: DayPart, val nation: String, val landmark: String, val from: String?) : DiaryEvent
    data class Advancement(override val part: DayPart, val person: PersonRef, val title: String, val rare: Boolean) : DiaryEvent
    data class Starforce(override val part: DayPart, val person: PersonRef, val item: String, val star: Int) : DiaryEvent
    data class VaccineShard(override val part: DayPart, val person: PersonRef) : DiaryEvent
}

/** 하루치 서버 요약. 개인별 접속 시간은 없고 집계만 있다. */
data class DaySummary(
    val serverInfectionBefore: Double,
    val serverInfectionAfter: Double,
    /** 국가별 어제 좀비 스코어 합. 1위 국가만 언급한다. */
    val nationScores: Map<String, Double> = emptyMap(),
    val vaccineShardsTotal: Int? = null,
    val fullMoons: Int = 0,
)

data class DayInput(
    val date: LocalDate,
    val dayNumber: Int,
    val events: List<DiaryEvent>,
    val summary: DaySummary,
)

enum class Voice {
    /** 생존자가 쓰는 일지. 평서체, 짧고 구체적, 감탄 없음. 'AI 티'를 줄이는 쪽. */
    SURVIVOR,
    /** 차분한 관찰자. 해요체, 정확하고 다정하며 가끔 건조한 농담. '클로드 느낌'. */
    OBSERVER,
}

data class Diary(val title: String, val paragraphs: List<String>) {
    fun text(): String = (listOf(title) + paragraphs).joinToString("\n\n")
}
