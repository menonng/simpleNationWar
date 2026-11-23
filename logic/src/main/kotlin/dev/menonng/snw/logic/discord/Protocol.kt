package dev.menonng.snw.logic.discord

import java.security.SecureRandom

/**
 * 모드 ↔ 봇 통신 규약 v1(docs/discord/protocol.md). JSON 라이브러리에 기대지 않는 규칙만 둔다:
 * 메시지 종류, 확인(ack)이 필요한 종류, 대기열에 남길 종류, 고유 id, 중복 거르기, 재연결 간격, 연동 코드.
 * 봉투: {"v":1,"id":"…","type":"…","ts":<epoch ms>,"data":{…}}
 */
object Protocol {
    const val VERSION = 1
    const val PATH = "/snw"
    const val HEADER_AUTH = "Authorization"
    const val HEADER_VERSION = "X-SNW-Protocol"
    const val PING_SECONDS = 30L
    const val CLOSE_POLICY = 1008

    // 모드 → 봇
    const val HELLO = "hello"; const val SEASON_START = "season.start"; const val JOIN = "player.join"; const val LEAVE = "player.leave"
    const val LINK_CODE = "link.code"; const val DEATH = "player.death"; const val REVENANT = "revenant.created"
    const val CLAIM = "nation.claim"; const val RELATION = "nation.relation"; const val LANDMARK = "landmark.captured"
    const val VACCINE = "vaccine.shard"; const val BLOODMOON_NOTICE = "zomapo.bloodmoon.notice"; const val BLOODMOON_START = "zomapo.bloodmoon.start"
    const val BLOODMOON_RESULT = "zomapo.bloodmoon.result"; const val NEST = "zomapo.nest"; const val DAILY = "zomapo.daily"
    const val STARFORCE = "starforce.success"; const val STATS = "stats.snapshot"; const val RATING_SUBMIT = "rating.submit"
    const val ALPHA = "zomapo.alpha"; const val RAID = "zomapo.raid"; const val FINALE = "zomapo.finale"; const val ENDING = "zomapo.ending"
    const val SERVER_EVENT = "server.event"
    // 봇 → 모드
    const val WELCOME = "welcome"; const val LINK_CONFIRMED = "link.confirmed"; const val LINK_REVOKED = "link.revoked"
    const val NICKNAME_UPDATED = "nickname.updated"; const val NATION_ASSIGN = "nation.assign"; const val RATING_REQUEST = "rating.request"
    const val ANNOUNCE = "announce"; const val STATS_REQUEST = "stats.request"
    const val ACK = "ack"

    /** 받는 쪽이 ack 를 돌려줘야 하는 종류(표의 ✔). */
    val NEEDS_ACK = setOf(SEASON_START, LINK_CODE, DEATH, RELATION, LANDMARK, VACCINE, RATING_SUBMIT, ENDING,
        LINK_CONFIRMED, LINK_REVOKED, NICKNAME_UPDATED, NATION_ASSIGN)
    /** 연결이 끊겨 있을 때 디스크에 남겼다가 다시 보낼 종류(잃으면 안 되는 것). */
    val DEFAULT_OUTBOX = setOf(DEATH, VACCINE, LANDMARK, ENDING)

    fun needsAck(type: String) = type in NEEDS_ACK

    /** 주 버전이 같은가(헤더 값). */
    fun compatible(header: String?): Boolean = header?.trim()?.substringBefore('.')?.toIntOrNull() == VERSION

    /** 재연결 간격(초): 1, 2, 4, … 최대 60. */
    fun backoffSeconds(attempt: Int): Long = minOf(60L, 1L shl attempt.coerceIn(0, 6))
}

/** 시간순으로 정렬되는 26자 고유 id(ULID 형식: 48비트 시각 + 80비트 난수, Crockford base32). */
object Ulid {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()
    fun next(now: Long = System.currentTimeMillis()): String {
        val sb = StringBuilder(26)
        var t = now
        val time = CharArray(10)
        for (i in 9 downTo 0) { time[i] = ALPHABET[(t and 31).toInt()]; t = t shr 5 }
        sb.append(time)
        repeat(16) { sb.append(ALPHABET[random.nextInt(32)]) }
        return sb.toString()
    }
}

/** 최근 [capacity] 개 id 를 기억해 중복 메시지를 거른다. */
class Dedup(private val capacity: Int = 2048) {
    private val seen = LinkedHashSet<String>()
    /** 처음 보는 id 면 true(기억한다), 이미 본 id 면 false. */
    @Synchronized fun firstTime(id: String): Boolean {
        if (!seen.add(id)) return false
        if (seen.size > capacity) seen.iterator().let { it.next(); it.remove() }
        return true
    }
}

/**
 * 연동 코드(6자리, 10분). 같은 UUID 가 10분 안에 다시 오면 같은 코드를 준다. 코드는 서로 겹치지 않는다.
 * 모드가 발급하고(접속 거부 화면), 봇이 같은 규칙으로 저장·확인한다.
 */
class LinkCodeBook(private val ttlMillis: Long = 10 * 60_000L, private val random: java.util.Random = SecureRandom()) {
    data class Code(val uuid: String, val name: String, val code: String, val expiresAt: Long)
    private val byUuid = HashMap<String, Code>()

    @Synchronized fun issue(uuid: String, name: String, now: Long): Code {
        prune(now)
        byUuid[uuid]?.let { return it }
        var code: String
        do { code = "%06d".format(random.nextInt(1_000_000)) } while (byUuid.values.any { it.code == code })
        return Code(uuid, name, code, now + ttlMillis).also { byUuid[uuid] = it }
    }

    @Synchronized fun put(c: Code) { byUuid[c.uuid] = c }

    /** 코드로 찾는다(만료·없음이면 null). 찾으면 지운다(한 번만 쓴다). */
    @Synchronized fun redeem(code: String, now: Long): Code? {
        prune(now)
        val c = byUuid.values.firstOrNull { it.code == code.trim() } ?: return null
        byUuid.remove(c.uuid)
        return c
    }

    @Synchronized fun prune(now: Long) { byUuid.values.removeIf { it.expiresAt <= now } }
    @Synchronized fun size(): Int = byUuid.size
}
