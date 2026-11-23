package dev.menonng.snw.bot

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.menonng.snw.diary.DayInput
import dev.menonng.snw.diary.DayPart
import dev.menonng.snw.diary.DaySummary
import dev.menonng.snw.diary.DiaryEvent
import dev.menonng.snw.diary.DiaryMemory
import dev.menonng.snw.diary.DiaryWriter
import dev.menonng.snw.diary.PersonRef
import dev.menonng.snw.diary.Voice
import dev.menonng.snw.diary.Zone
import dev.menonng.snw.logic.discord.Protocol
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * '오늘의 서버 기록'(diary.md): 어제 하루의 사건을 일지 모듈 입력으로 바꿔 글을 만든다. 외부 AI 를 쓰지 않는다.
 * 사람은 닉네임과 표시 동의만, 시각은 하루의 다섯 구간으로만 넘긴다. 좌표는 처음부터 받지 않는다.
 */
class DiaryJob(private val db: Db, private val zone: ZoneId = ZoneId.of("Asia/Seoul"), private val seasonStart: LocalDate = LocalDate.of(2027, 1, 16)) {
    private val gson = Gson()

    private fun person(uuid: String?): PersonRef? {
        val u = uuid?.let { db.userByUuid(it) } ?: return null
        val hash = MessageDigest.getInstance("SHA-256").digest(u.discordId.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
        return PersonRef(hash, u.nickname, u.nation ?: "", u.showName, u.funnyOptIn)
    }

    fun input(day: LocalDate): DayInput {
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val until = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val events = ArrayList<DiaryEvent>()
        var shardsTotal: Int? = null
        for ((type, payload, ts) in db.events(from, until)) {
            val d: JsonObject = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull() ?: continue
            val part = DayPart.ofHour(Instant.ofEpochMilli(ts).atZone(zone).hour)
            fun s(k: String) = d[k]?.takeIf { !it.isJsonNull }?.asString
            when (type) {
                Protocol.DEATH -> if (d["pvp"]?.asBoolean == true) {
                    val k = person(s("killer")); val v = person(s("victim"))
                    if (k != null && v != null) events += DiaryEvent.PvpKill(part, k, v, d.getAsJsonArray("assists")?.mapNotNull { person(it.asString) }.orEmpty(), Zone.Unknown)
                }
                Protocol.VACCINE -> { person(s("person"))?.let { events += DiaryEvent.VaccineShard(part, it) }; d["total"]?.asInt?.let { shardsTotal = it } }
                Protocol.ALPHA -> events += DiaryEvent.AlphaKilled(part, emptyList(), Zone.Unknown)
                Protocol.NEST -> if (s("event") == "destroyed") events += DiaryEvent.NestDestroyed(part, emptyList(), Zone.Nest)
                Protocol.BLOODMOON_RESULT -> d.getAsJsonArray("results")?.forEach { r -> val o = r.asJsonObject
                    events += DiaryEvent.BloodMoon(part, o["success"]?.asBoolean == true, emptyList(), o["nation"]?.asString ?: "") }
                Protocol.LANDMARK -> events += DiaryEvent.LandmarkCaptured(part, s("to") ?: "", s("landmark") ?: "", s("from"))
                Protocol.STARFORCE -> person(s("uuid"))?.let { events += DiaryEvent.Starforce(part, it, s("item") ?: "장비", d["star"]?.asInt ?: 10) }
            }
        }
        val before = db.get("infection.before")?.toDoubleOrNull() ?: 0.0
        val after = db.get("daily")?.let { runCatching { JsonParser.parseString(it).asJsonObject["wild"].asDouble }.getOrNull() } ?: before
        val dayNumber = ChronoUnit.DAYS.between(seasonStart, day).toInt() + 1
        return DayInput(day, dayNumber, events, DaySummary(before, after, vaccineShardsTotal = shardsTotal))
    }

    /** 어제 일지를 만든다. 기억을 갱신·저장하고 글을 돌려준다. */
    fun write(day: LocalDate): String {
        val memory = db.get("diary.memory")?.let { runCatching { gson.fromJson(it, DiaryMemory::class.java) }.getOrNull() } ?: DiaryMemory()
        val inp = input(day)
        val out = DiaryWriter(Voice.CLAUDE, salt = "snw").write(inp, memory)
        db.put("diary.memory", gson.toJson(out.memory))
        db.put("infection.before", inp.summary.serverInfectionAfter.toString())
        return out.diary.text()
    }
}
