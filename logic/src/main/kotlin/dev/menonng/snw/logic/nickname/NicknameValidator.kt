package dev.menonng.snw.logic.nickname

import java.text.Normalizer

/**
 * 커스텀 닉네임 검사. docs/design/nickname.md.
 *
 * 형식 규칙(길이·문자·중복)과 함께 **닉네임에 들어간 개인정보**를 찾는다.
 * 확실한 것(연락처, 4자리 이상 숫자, 학교·학년 표기)은 거절하고,
 * 실명·출생연도처럼 보이지만 아닐 수도 있는 것은 운영자 확인으로 돌린다.
 * 규칙으로 모든 개인정보를 잡을 수는 없으므로 가입 모달의 자기 확인과 운영자 승인을 함께 쓴다.
 */
class NicknameValidator(
    private val bannedWords: Set<String> = emptySet(),
    private val minLength: Int = 2,
    private val maxLength: Int = 10,
) {
    enum class Verdict { ACCEPT, REVIEW, REJECT }

    enum class Reason(val message: String) {
        LENGTH("2~10자로 정해 주십시오"),
        CHARSET("한글(완성형), 영문, 숫자, 밑줄만 쓸 수 있습니다"),
        DUPLICATE("이미 다른 사람이 쓰고 있습니다"),
        MC_NAME_CLASH("다른 사람의 마인크래프트 이름과 같습니다"),
        BANNED("쓸 수 없는 낱말이 들어 있습니다"),
        LONG_DIGITS("숫자를 4자리 이상 이어 쓸 수 없습니다(전화번호·생년월일 보호)"),
        TOO_MANY_DIGITS("숫자는 2개까지만 쓸 수 있습니다"),
        CONTACT("연락처·계정을 떠올리게 하는 낱말은 쓸 수 없습니다"),
        SCHOOL("학교·학년·반 표기는 쓸 수 없습니다"),
        BIRTH_YEAR("출생연도처럼 보이는 숫자가 있습니다"),
        REAL_NAME("실명처럼 보입니다"),
        REAL_NAME_ROMAN("실명을 영문으로 쓴 것처럼 보입니다"),
        REGION("사는 곳을 떠올리게 하는 표기가 있습니다"),
    }

    data class Result(val verdict: Verdict, val reasons: List<Reason>, val normalized: String)

    data class Context(
        val takenNicknames: Set<String> = emptySet(),
        val otherMinecraftNames: Set<String> = emptySet(),
    )

    fun check(raw: String, context: Context = Context()): Result {
        val nick = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC)
        val key = normalizeKey(nick)
        val reject = mutableListOf<Reason>()
        val review = mutableListOf<Reason>()

        if (nick.length !in minLength..maxLength) reject += Reason.LENGTH
        if (!nick.all { it in '가'..'힣' || it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }) reject += Reason.CHARSET
        if (context.takenNicknames.any { normalizeKey(it) == key }) reject += Reason.DUPLICATE
        if (context.otherMinecraftNames.any { normalizeKey(it) == key }) reject += Reason.MC_NAME_CLASH
        val compact = key.replace("_", "")
        if (bannedWords.any { it.isNotBlank() && compact.contains(normalizeKey(it).replace("_", "")) }) reject += Reason.BANNED

        checkDigits(nick, reject, review)
        val tokens = key.split('_').filter { it.isNotEmpty() }
        if (CONTACT_WORDS.any { compact.contains(it) } || CONTACT_TOKENS.any { it in tokens || key == it }) reject += Reason.CONTACT
        when (schoolSignal(nick)) {
            Signal.STRONG -> reject += Reason.SCHOOL
            Signal.WEAK -> review += Reason.SCHOOL
            Signal.NONE -> Unit
        }
        if (looksLikeRegion(nick)) review += Reason.REGION
        if (looksLikeKoreanName(nick)) review += Reason.REAL_NAME
        if (looksLikeRomanizedName(key)) review += Reason.REAL_NAME_ROMAN

        val verdict = when {
            reject.isNotEmpty() -> Verdict.REJECT
            review.isNotEmpty() -> Verdict.REVIEW
            else -> Verdict.ACCEPT
        }
        return Result(verdict, (reject + review).distinct(), nick)
    }

    private fun checkDigits(nick: String, reject: MutableList<Reason>, review: MutableList<Reason>) {
        val runs = Regex("[0-9]+").findAll(nick).map { it.value }.toList()
        if (runs.any { it.length >= 4 }) {
            reject += Reason.LONG_DIGITS
            return
        }
        val total = runs.sumOf { it.length }
        if (total > 2) {
            reject += Reason.TOO_MANY_DIGITS
            return
        }
        // 두 자리 숫자는 출생연도(예: 08, 97)일 수 있다
        if (runs.any { it.length == 2 && it.toInt().let { y -> y in 0..15 || y in 80..99 } }) review += Reason.BIRTH_YEAR
    }

    private enum class Signal { NONE, WEAK, STRONG }

    private fun schoolSignal(nick: String): Signal {
        if (SCHOOL_WORDS.any { nick.contains(it) }) return Signal.STRONG
        // "3학년", "2반", "5학년3반"
        if (Regex("[0-9]+(학년|반)").containsMatchIn(nick)) return Signal.STRONG
        // "서울고", "한빛중", "대한초": 한글 2~4자 + 학교급 한 글자로 끝나는 닉네임 전체. 흔한 낱말은 제외하고 운영자 확인으로.
        val m = Regex("^[가-힣]{2,4}(초|중|고)$").find(nick) ?: return Signal.NONE
        return if (m.value in SCHOOL_FALSE_POSITIVES) Signal.NONE else Signal.WEAK
    }

    private fun looksLikeRegion(nick: String): Boolean =
        REGION_NAMES.any { nick.contains(it) } || Regex("[가-힣]{2,}[0-9]+(동|가)").containsMatchIn(nick)

    /** 흔한 성씨로 시작하는 한글 3자(또는 복성 + 2자)만으로 된 닉네임. */
    private fun looksLikeKoreanName(nick: String): Boolean {
        val hangul = nick.filter { it in '가'..'힣' }
        if (hangul != nick.replace("_", "").filter { !it.isDigit() }) return false
        if (hangul.length == 4 && DOUBLE_SURNAMES.any { hangul.startsWith(it) }) return true
        return hangul.length == 3 && hangul[0] in SURNAMES && hangul !in NAME_FALSE_POSITIVES
    }

    /** kim_minsu, minsukim, parkjiwoo 처럼 로마자 성 + 이름 꼴. */
    private fun looksLikeRomanizedName(key: String): Boolean {
        val letters = key.filter { it in 'a'..'z' || it == '_' }
        if (letters.length != key.length) return false
        val parts = letters.split('_').filter { it.isNotEmpty() }
        if (parts.size == 2 && (parts[0] in ROMAN_SURNAMES || parts[1] in ROMAN_SURNAMES) && parts.all { it.length in 2..8 }) return true
        val flat = letters.replace("_", "")
        if (flat.length !in 6..12) return false
        val start = ROMAN_SURNAMES.firstOrNull { flat.startsWith(it) && looksLikeRomanGivenName(flat.removePrefix(it)) }
        val end = ROMAN_SURNAMES.firstOrNull { flat.endsWith(it) && looksLikeRomanGivenName(flat.removeSuffix(it)) }
        return start != null || end != null
    }

    /** 로마자 한국 이름 음절 두 개(minsu, jiwoo, seoyeon …)로 나뉘는가. */
    private fun looksLikeRomanGivenName(s: String): Boolean {
        if (s.length !in 4..9) return false
        for (i in 2..s.length - 2) {
            if (s.substring(0, i) in ROMAN_SYLLABLES && s.substring(i) in ROMAN_SYLLABLES) return true
        }
        return false
    }

    companion object {
        fun normalizeKey(s: String): String = Normalizer.normalize(s.trim(), Normalizer.Form.NFC).lowercase()

        /** 어디에 들어 있어도 연락처를 뜻하는 낱말. */
        private val CONTACT_WORDS = listOf(
            "naver", "gmail", "hanmail", "kakao", "insta", "discord", "telegram", "twitter", "tiktok", "youtube", "phone", "email",
            "네이버", "지메일", "한메일", "카톡", "카카오", "인스타", "디코", "텔레그램", "트위터", "틱톡", "유튜브", "전번", "번호", "연락",
        )

        /** 짧아서 다른 낱말 안에 흔히 들어가므로, 밑줄로 나뉜 조각이 정확히 같을 때만 본다(online, recall 은 통과). */
        private val CONTACT_TOKENS = listOf("line", "call", "dm", "mail", "daum", "tel")

        private val SCHOOL_WORDS = listOf("학교", "학년", "고등", "중학", "초등", "대학", "학번", "반장", "교복")
        private val SCHOOL_FALSE_POSITIVES = setOf(
            "그리고", "제일최고", "치킨최고", "짱최고", "역시최고", "그래도", "그러고", "먹고", "싸우고", "살아남고", "도망중", "생존중",
            "탐험중", "사냥중", "건축중", "잠수중", "공사중", "로딩중", "회의중", "작업중", "수리중", "고민중", "휴식중", "출장중",
            "외출중", "잠자는중", "배고프고", "졸리고", "바보초", "꼬마초",
        )
        private val REGION_NAMES = listOf("서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종", "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주")

        /**
         * 흔한 성씨(한 글자). 인구가 많은 성 위주로 잡고, '하·주·우·구·도·소·마·기'처럼 일반 낱말 첫 글자로 흔한 것은 뺐다.
         * 걸리면 거절이 아니라 운영자 확인이다.
         */
        private val SURNAMES: Set<Char> = "김이박최정강조윤장임한오서신권황안송류유전홍고문양손배백허남심노곽성차민진엄채원천방공현".toSet()
        private val DOUBLE_SURNAMES = listOf("남궁", "황보", "제갈", "선우", "독고", "사공", "서문")

        /** 성씨로 시작하지만 흔한 낱말·별명인 것. */
        private val NAME_FALSE_POSITIVES = setOf(
            "고양이", "강아지", "도토리", "나무늘", "구름이", "하늘이", "바다새", "마법사", "기사단", "고구마", "감자칩", "이불속", "유령이",
            "정찰병", "조각가", "장난감", "임금님", "한밤중", "오징어", "서리꽃", "신호등", "권투왕", "황금빛", "안개꽃", "송사리",
            "전투력", "홍당무", "문지기", "양치기", "손오공", "배고파", "백호랑", "허수아", "남쪽새", "심해어", "노을빛", "하마왕",
            "곽철이", "성기사", "차가운", "주먹왕", "우주인", "구조대", "민트초", "진눈깨", "나비야", "지렁이", "엄청난", "변신로",
            "채식주", "원숭이", "천둥새", "방랑자", "공룡왕", "현자님", "함박눈", "염소왕", "여우비", "추락왕", "도깨비", "소나무",
            "석양빛", "선인장", "설탕물", "마왕님", "길잡이", "연금술", "위대한", "표범왕", "명탐정", "기러기", "반딧불", "왕초보",
            "금붕어", "옥수수", "육각형", "인어왕", "맹수왕", "제비꽃", "모험가", "탁구왕", "국밥왕", "어부왕", "은하수", "편의점",
            "용사님", "예언자", "경비병", "봉구스", "사냥꾼", "부엉이", "좀비왕", "생존왕",
        )

        private val ROMAN_SURNAMES = listOf(
            "kim", "lee", "yi", "park", "pak", "choi", "choe", "jung", "jeong", "chung", "kang", "cho", "jo", "yoon", "yun",
            "jang", "chang", "lim", "im", "han", "oh", "seo", "suh", "shin", "sin", "kwon", "hwang", "ahn", "an", "song",
            "ryu", "yoo", "yu", "jeon", "jun", "hong", "ko", "go", "moon", "mun", "yang", "son", "sohn", "bae", "baek", "paik",
            "heo", "huh", "nam", "noh", "roh", "ha", "kwak", "sung", "cha", "joo", "ju", "woo", "koo", "ku", "min", "jin",
            "na", "ji", "um", "eom", "byun", "chae", "won", "chun", "bang", "kong", "hyun", "ham", "yeom", "yeo", "choo",
            "do", "so", "seok", "sun", "seol", "ma", "gil", "yeon", "wi", "pyo", "myung", "ki", "ban", "wang", "keum", "ok",
        ).sortedByDescending { it.length }

        private val ROMAN_SYLLABLES = setOf(
            "min", "su", "soo", "ji", "woo", "wu", "seo", "yeon", "yun", "hyun", "jun", "joon", "hoon", "hun", "young", "yeong",
            "ho", "jin", "ha", "eun", "in", "ah", "a", "yeong", "won", "hee", "hye", "seung", "sung", "jae", "jea", "dong",
            "kyung", "gyeong", "hyeon", "chan", "bin", "been", "yoon", "na", "ra", "rin", "da", "do", "hwa", "mi", "so", "sol",
            "song", "sang", "tae", "gyu", "kyu", "yu", "ye", "jeong", "jung", "il", "hyuk", "hyeok", "beom", "bum", "geon",
            "gun", "jong", "woong", "ung", "si", "sik", "seon", "sun", "bo", "eon", "ri", "yul", "ju", "joo", "ae", "ok",
        )
    }
}
