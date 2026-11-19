package dev.menonng.snw.diary

import dev.menonng.snw.logic.text.KoreanJosa
import kotlin.random.Random

/** 틀 문법을 채운다. 문법은 [Phrase] 설명 참고. */
object TemplateRenderer {
    private val conditional = Regex("""\(\?([a-zA-Z]+):([^()]*)\)""")
    private val alternatives = Regex("""\[([^\[\]]*)]""")
    private val placeholder = Regex("""\{([a-zA-Z]+)(?::([^}]*))?}""")

    fun render(template: String, vars: Map<String, String?>, random: Random): String {
        var s = conditional.replace(template) { m -> if (vars[m.groupValues[1]].isNullOrEmpty()) "" else m.groupValues[2] }
        s = alternatives.replace(s) { m -> m.groupValues[1].split('|').let { it[random.nextInt(it.size)] } }
        s = placeholder.replace(s) { m ->
            val value = vars[m.groupValues[1]] ?: error("값 없음: ${m.groupValues[1]} in $template")
            KoreanJosa.apply(value, m.groupValues[2])
        }
        return s.replace(Regex(" {2,}"), " ").trim()
    }

    /** 틀이 요구하는 값(조건부 제외)이 모두 있는가. */
    fun requires(template: String): Set<String> {
        val unconditional = conditional.replace(template, "")
        return placeholder.findAll(unconditional).map { it.groupValues[1] }.toSet()
    }
}

/**
 * 'AI 티'와 목소리 일관성을 검사한다. 위반마다 벌점을 매기고, 작가는 벌점이 가장 낮은 판본을 고른다.
 */
object StyleLinter {
    /** 생성형 AI 글에서 자주 보이는 상투어. 두 목소리 모두 금지. */
    val banned = listOf(
        "흥미롭게도", "놀랍게도", "결론적으로", "요약하자면", "한편", "마치", "여정", "눈부신", "다양한", "중요한 것은",
        "물론", "그야말로", "라고 할 수 있", "것이 중요", "잊지 못할", "새로운 장", "한 편의", "드라마", "서사", "역사적인",
        "!!", "—", "~", "…", "ㅋㅋ", "ㅎㅎ", "🙂", "😊", "✨", "🔥",
    )

    data class Report(val penalty: Int, val problems: List<String>)

    fun check(sentences: List<String>, voice: Voice): Report {
        val problems = mutableListOf<String>()
        var penalty = 0
        val text = sentences.joinToString(" ")
        for (b in banned) if (text.contains(b)) { problems += "상투어: $b"; penalty += 10 }
        val bangs = text.count { it == '!' }
        if (voice == Voice.SURVIVOR && bangs > 0) { problems += "느낌표"; penalty += 5 * bangs }
        if (voice == Voice.OBSERVER && bangs > 1) { problems += "느낌표 과다"; penalty += 5 * (bangs - 1) }

        for (s in sentences.flatMap { splitSentences(it) }) {
            val polite = s.endsWith("요.") || s.endsWith("요?")
            if (voice == Voice.SURVIVOR && polite) { problems += "해요체 섞임: $s"; penalty += 8 }
            if (voice == Voice.OBSERVER && !polite && !s.endsWith(".")) { penalty += 1 }
            if (voice == Voice.OBSERVER && s.endsWith("다.")) { problems += "평서체 섞임: $s"; penalty += 8 }
            if (s.count { it == ',' } >= 3) { problems += "쉼표 나열: $s"; penalty += 3 }
        }
        val flat = sentences.flatMap { splitSentences(it) }
        for (i in 1 until flat.size) {
            if (firstWord(flat[i]) == firstWord(flat[i - 1])) { problems += "같은 첫 낱말: ${firstWord(flat[i])}"; penalty += 2 }
            if (lastWord(flat[i]) == lastWord(flat[i - 1])) { problems += "같은 끝 낱말: ${lastWord(flat[i])}"; penalty += 2 }
        }
        return Report(penalty, problems)
    }

    /** 한 틀 안에 문장이 여럿일 수 있어 마침표 기준으로 나눈다(숫자 소수점은 건너뜀). */
    fun splitSentences(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (i in s.indices) {
            sb.append(s[i])
            val end = s[i] == '.' || s[i] == '?' || s[i] == '!'
            val decimal = s[i] == '.' && i > 0 && i + 1 < s.length && s[i - 1].isDigit() && s[i + 1].isDigit()
            if (end && !decimal) {
                out += sb.toString().trim()
                sb.clear()
            }
        }
        if (sb.isNotBlank()) out += sb.toString().trim()
        return out.filter { it.isNotEmpty() }
    }

    private fun firstWord(s: String) = s.trim().split(' ').first().filter { it.isLetterOrDigit() }.take(2)
    private fun lastWord(s: String) = s.trim().trimEnd('.', '?', '!').split(' ').last()
}
