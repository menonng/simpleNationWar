package dev.menonng.snw.logic.text

/**
 * 한국어 조사 고르기. 앞 낱말의 마지막 글자에 받침이 있는지(그리고 ㄹ 받침인지)로 정한다.
 * 한글이 아닌 끝 글자(숫자, 영문)는 읽는 소리로 판단한다.
 */
object KoreanJosa {
    enum class Ending { VOWEL, CONSONANT, RIEUL }

    fun ending(word: String): Ending {
        val c = word.trimEnd().lastOrNull { it.isLetterOrDigit() } ?: return Ending.VOWEL
        if (c in '가'..'힣') {
            val jong = (c.code - 0xAC00) % 28
            return when (jong) {
                0 -> Ending.VOWEL
                8 -> Ending.RIEUL
                else -> Ending.CONSONANT
            }
        }
        if (c.isDigit()) {
            return when (c) {
                '1', '7', '8' -> Ending.RIEUL          // 일 칠 팔
                '0', '3', '6' -> Ending.CONSONANT      // 영 삼 육
                else -> Ending.VOWEL                   // 이 사 오 구
            }
        }
        val lower = word.trimEnd().lowercase()
        val last = lower.last { it.isLetterOrDigit() }
        if (word.trimEnd().length >= 2 && word.trimEnd().all { !it.isLetter() || it.isUpperCase() }) {
            // 약어는 알파벳 이름으로 읽는다: L 엘, M 엠, N 엔, R 알
            return when (last) {
                'l', 'r' -> Ending.RIEUL
                'm', 'n' -> Ending.CONSONANT
                else -> Ending.VOWEL
            }
        }
        return when {
            lower.endsWith("ng") -> Ending.CONSONANT
            last == 'l' -> Ending.RIEUL
            last == 'm' || last == 'n' -> Ending.CONSONANT
            last == 'k' || last == 'p' || last == 't' -> Ending.CONSONANT   // book 북, cup 컵, cat 캣
            else -> Ending.VOWEL
        }
    }

    private fun pick(word: String, consonant: String, vowel: String, rieulLikeVowel: Boolean = false): String {
        val e = ending(word)
        val useVowel = e == Ending.VOWEL || (rieulLikeVowel && e == Ending.RIEUL)
        return word + if (useVowel) vowel else consonant
    }

    fun eunNeun(word: String) = pick(word, "은", "는")
    fun iGa(word: String) = pick(word, "이", "가")
    fun eulReul(word: String) = pick(word, "을", "를")
    fun gwaWa(word: String) = pick(word, "과", "와")
    fun irangRang(word: String) = pick(word, "이랑", "랑")
    fun euroRo(word: String) = pick(word, "으로", "로", rieulLikeVowel = true)
    fun aYa(word: String) = pick(word, "아", "야")
    fun ina(word: String) = pick(word, "이나", "나")
    /** 서술격 조사 과거형: 철수였다 / 민준이었다. */
    fun copulaPast(word: String) = pick(word, "이었다", "였다")
    /** 서술격 조사 해요체: 철수예요 / 민준이에요. */
    fun copulaPolite(word: String) = pick(word, "이에요", "예요")

    /** "철수, 영희와 민수" 처럼 잇는다. 마지막 접속 조사는 그 앞 낱말에 맞춘다. */
    fun join(words: List<String>, conj: (String) -> String = ::gwaWa): String = when (words.size) {
        0 -> ""
        1 -> words[0]
        else -> words.dropLast(1).dropLast(1).joinToString("") { "$it, " } + conj(words[words.size - 2]) + " " + words.last()
    }

    /** {이름:은/는} 형식의 자리표시를 채운다. */
    fun apply(name: String, marker: String): String = when (marker) {
        "은/는", "은", "는" -> eunNeun(name)
        "이/가", "이", "가" -> iGa(name)
        "을/를", "을", "를" -> eulReul(name)
        "과/와", "과", "와" -> gwaWa(name)
        "이랑/랑" -> irangRang(name)
        "으로/로", "로" -> euroRo(name)
        "아/야" -> aYa(name)
        "이나/나" -> ina(name)
        "이었다/였다" -> copulaPast(name)
        "이에요/예요" -> copulaPolite(name)
        "" -> name
        else -> name + marker
    }
}
