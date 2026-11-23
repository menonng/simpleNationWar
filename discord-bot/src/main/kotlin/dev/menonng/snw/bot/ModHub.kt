package dev.menonng.snw.bot

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import dev.menonng.snw.logic.discord.AlertText
import dev.menonng.snw.logic.discord.Dedup
import dev.menonng.snw.logic.discord.Protocol
import dev.menonng.snw.logic.discord.Ulid
import dev.menonng.snw.logic.nickname.NicknameValidator
import java.time.Instant
import java.time.ZoneId

/** 디스코드 쪽에 보일 것(DiscordFront 가 구현, 시험에서는 가짜). */
interface Front {
    fun alert(text: String)
    fun serverStatus(online: Boolean)
    fun linked(discordId: String, nickname: String)
    fun review(discordId: String, nickname: String, reasons: List<String>) {}
}

/**
 * 모드와 주고받는 규약 처리와 가입·연동 규칙(디스코드 라이브러리에 기대지 않는다). docs/discord/protocol.md, README '가입 흐름'.
 * - 연결되면 모드가 hello → 봇이 welcome(연동 목록·닉네임·국가 배정 전체).
 * - 확인(ack)이 필요한 메시지는 받으면 ack 를 돌려주고, 보낸 것은 ack 가 올 때까지 기억했다가 다시 연결되면 다시 보낸다.
 * - 사건은 id 로 중복을 거르고 기록한 뒤 #알림 문장으로 바꾼다(좌표 없음).
 */
class ModHub(private val db: Db, private val front: Front, private val validator: NicknameValidator = NicknameValidator(),
             private val zone: ZoneId = ZoneId.of("Asia/Seoul")) {
    private val gson = Gson()
    private val dedup = Dedup()
    private val pending = java.util.Collections.synchronizedMap(LinkedHashMap<String, String>())
    @Volatile var sink: ((String) -> Unit)? = null
    @Volatile var connected = false; private set
    val online: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    // ------------------------------------------------------------------ 연결

    fun onConnect(send: (String) -> Unit) { sink = send; connected = true; front.serverStatus(true) }
    fun onDisconnect() { sink = null; connected = false; online.clear(); front.serverStatus(false) }

    fun send(type: String, data: Map<String, Any?>): String {
        val id = Ulid.next()
        val json = gson.toJson(linkedMapOf("v" to Protocol.VERSION, "id" to id, "type" to type, "ts" to System.currentTimeMillis(), "data" to data))
        if (Protocol.needsAck(type)) pending[id] = json
        sink?.invoke(json)
        return id
    }

    private fun ack(ref: String) { sink?.invoke(gson.toJson(mapOf("v" to Protocol.VERSION, "id" to Ulid.next(), "type" to Protocol.ACK, "ts" to System.currentTimeMillis(), "ref" to ref, "data" to emptyMap<String, Any>()))) }

    fun pendingCount(): Int = pending.size

    private fun welcome() {
        val users = db.linkedUsers()
        send(Protocol.WELCOME, mapOf(
            "links" to users.map { mapOf("uuid" to it.mcUuid, "discordId" to it.discordId, "nickname" to it.nickname) },
            "nations" to users.filter { it.nation != null }.map { mapOf("uuid" to it.mcUuid, "nation" to it.nation, "role" to (it.role ?: "CITIZEN")) },
        ))
        synchronized(pending) { pending.values.toList() }.forEach { sink?.invoke(it) }
    }

    // ------------------------------------------------------------------ 받기

    private val mapType = object : TypeToken<Map<String, Any?>>() {}.type

    fun onText(text: String) {
        val o = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
        val type = o["type"]?.asString ?: return
        if (type == Protocol.ACK) { o["ref"]?.asString?.let { pending.remove(it) }; return }
        val id = o["id"]?.asString ?: return
        val ts = o["ts"]?.asLong ?: System.currentTimeMillis()
        if (!dedup.firstTime(id)) { if (Protocol.needsAck(type)) ack(id); return }
        val d: JsonObject = o["data"]?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val data: Map<String, Any?> = gson.fromJson(d, mapType)
        when (type) {
            Protocol.HELLO -> { online.clear(); d.getAsJsonArray("online")?.forEach { online += it.asString }; noteOnline(ts); welcome() }
            Protocol.JOIN -> { online += data["uuid"] as String; noteOnline(ts) }
            Protocol.LEAVE -> { online -= data["uuid"] as String }
            Protocol.LINK_CODE -> db.putCode(data["code"] as String, data["uuid"] as String, data["name"] as? String, (data["expiresAt"] as Number).toLong())
            Protocol.STATS -> db.put("stats", d.toString())
            else -> {
                if (!db.recordEvent(id, type, d.toString(), ts)) { if (Protocol.needsAck(type)) ack(id); return }
                if (type == Protocol.DAILY) db.put("daily", d.toString())
                AlertText.render(type, data) { uuid -> uuid?.let { db.userByUuid(it)?.nickname } }?.let { front.alert(it) }
            }
        }
        if (Protocol.needsAck(type)) ack(id)
    }

    private fun noteOnline(ts: Long) {
        val t = Instant.ofEpochMilli(ts).atZone(zone)
        db.noteOnline(t.toLocalDate().toString(), t.hour, online.size)
    }

    // ------------------------------------------------------------------ 가입·연동

    sealed interface RegisterResult {
        data class Ok(val nickname: String, val uuid: String) : RegisterResult
        data class Review(val nickname: String, val reasons: List<String>) : RegisterResult
        data class Rejected(val reasons: List<String>) : RegisterResult
        data object UnknownMinecraftName : RegisterResult
        data object MinecraftTaken : RegisterResult
    }

    /**
     * 등록 모달 제출. [lookup] 은 마크 이름 → UUID(모장 프로필). 운영자 확인 판정이면 저장은 하되 운영진에게 넘긴다.
     */
    fun register(discordId: String, mcName: String, nickname: String, showName: Boolean, funnyOptIn: Boolean,
                 availability: String?, weeklyBand: String?, lookup: (String) -> String?, now: Long = System.currentTimeMillis()): RegisterResult {
        val uuid = lookup(mcName.trim()) ?: return RegisterResult.UnknownMinecraftName
        db.userByUuid(uuid)?.let { if (it.discordId != discordId) return RegisterResult.MinecraftTaken }
        val others = db.allUsers().filter { it.discordId != discordId }
        val r = validator.check(nickname, NicknameValidator.Context(others.map { it.nickname }.toSet(), others.mapNotNull { it.mcName }.toSet()))
        if (r.verdict == NicknameValidator.Verdict.REJECT) return RegisterResult.Rejected(r.reasons.map { it.message })
        val old = db.user(discordId)
        db.upsertUser(Db.User(discordId, uuid, mcName.trim(), r.normalized, showName, funnyOptIn, availability, weeklyBand, old?.registeredAt ?: now, old?.linkedAt, old?.nation, old?.role))
        return if (r.verdict == NicknameValidator.Verdict.REVIEW) RegisterResult.Review(r.normalized, r.reasons.map { it.message }).also { front.review(discordId, r.normalized, it.reasons) }
               else RegisterResult.Ok(r.normalized, uuid)
    }

    sealed interface LinkResult {
        data object NotRegistered : LinkResult
        data object BadCode : LinkResult
        data object Mismatch : LinkResult
        data class Linked(val nickname: String, val uuid: String) : LinkResult
    }

    /** [연동 코드 입력] 제출: 코드의 UUID 가 등록한 마크 계정과 같으면 연동. */
    fun redeem(discordId: String, code: String, now: Long = System.currentTimeMillis()): LinkResult {
        val u = db.user(discordId) ?: return LinkResult.NotRegistered
        val (uuid, _) = db.redeemCode(code, now) ?: return LinkResult.BadCode
        if (u.mcUuid != uuid) return LinkResult.Mismatch
        db.upsertUser(u.copy(linkedAt = now))
        send(Protocol.LINK_CONFIRMED, mapOf("uuid" to uuid, "discordId" to discordId, "nickname" to u.nickname))
        front.linked(discordId, u.nickname)
        return LinkResult.Linked(u.nickname, uuid)
    }

    /** 닉네임 바꾸기(운영자 승인 뒤 부른다). */
    fun changeNickname(discordId: String, nickname: String): RegisterResult {
        val u = db.user(discordId) ?: return RegisterResult.Rejected(listOf("등록되지 않은 사용자입니다"))
        val others = db.allUsers().filter { it.discordId != discordId }
        val r = validator.check(nickname, NicknameValidator.Context(others.map { it.nickname }.toSet(), others.mapNotNull { it.mcName }.toSet()))
        if (r.verdict == NicknameValidator.Verdict.REJECT) return RegisterResult.Rejected(r.reasons.map { it.message })
        db.upsertUser(u.copy(nickname = r.normalized))
        if (u.linkedAt != null && u.mcUuid != null) send(Protocol.NICKNAME_UPDATED, mapOf("uuid" to u.mcUuid, "nickname" to r.normalized))
        return RegisterResult.Ok(r.normalized, u.mcUuid ?: "")
    }

    /** 국가 배정(팀 확정 뒤): discordId → (국가 id, 역할). */
    fun assignNations(assignments: Map<String, Pair<String, String>>, names: Map<String, String>) {
        val list = assignments.mapNotNull { (did, nr) ->
            val u = db.user(did) ?: return@mapNotNull null
            db.upsertUser(u.copy(nation = nr.first, role = nr.second))
            u.mcUuid?.let { mapOf("uuid" to it, "nation" to nr.first, "role" to nr.second) }
        }
        send(Protocol.NATION_ASSIGN, mapOf("assignments" to list, "nationNames" to names))
    }

    fun announce(text: String, title: String? = null) { send(Protocol.ANNOUNCE, mapOf("text" to text, "title" to title)) }
    fun requestStats() { send(Protocol.STATS_REQUEST, emptyMap()) }
}
