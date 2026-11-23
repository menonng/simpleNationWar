package dev.menonng.snw.bot

import java.sql.Connection
import java.sql.DriverManager

/**
 * SQLite 저장소(README 6절 표의 일부). 개인별 접속·퇴장 시각은 저장하지 않고 시간대별 접속자 수만 센다.
 * 좌표는 받지도 저장하지도 않는다.
 */
class Db(url: String) : AutoCloseable {
    private val c: Connection = DriverManager.getConnection(url)

    data class User(val discordId: String, val mcUuid: String?, val mcName: String?, val nickname: String, val showName: Boolean, val funnyOptIn: Boolean,
                    val availability: String?, val weeklyBand: String?, val registeredAt: Long, val linkedAt: Long?, val nation: String? = null, val role: String? = null)

    init {
        c.createStatement().use { s ->
            s.executeUpdate("""CREATE TABLE IF NOT EXISTS users(discord_id TEXT PRIMARY KEY, mc_uuid TEXT, mc_name TEXT, nickname TEXT NOT NULL,
                show_name INTEGER NOT NULL DEFAULT 1, funny_opt_in INTEGER NOT NULL DEFAULT 0, availability TEXT, weekly_band TEXT,
                registered_at INTEGER NOT NULL, linked_at INTEGER, nation TEXT, role TEXT)""")
            s.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS users_nick ON users(nickname)")
            s.executeUpdate("CREATE TABLE IF NOT EXISTS link_codes(code TEXT PRIMARY KEY, mc_uuid TEXT NOT NULL, mc_name TEXT, expires_at INTEGER NOT NULL)")
            s.executeUpdate("CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY, type TEXT NOT NULL, payload TEXT NOT NULL, ts INTEGER NOT NULL)")
            s.executeUpdate("CREATE TABLE IF NOT EXISTS activity_hourly(date TEXT NOT NULL, hour INTEGER NOT NULL, online_count INTEGER NOT NULL, PRIMARY KEY(date, hour))")
            s.executeUpdate("CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, v TEXT)")
            s.executeUpdate("CREATE TABLE IF NOT EXISTS rating_rounds(id INTEGER PRIMARY KEY AUTOINCREMENT, status TEXT NOT NULL, categories TEXT NOT NULL, started_at INTEGER NOT NULL, deadline INTEGER NOT NULL, reminded INTEGER NOT NULL DEFAULT 0)")
            s.executeUpdate("CREATE TABLE IF NOT EXISTS ratings(round_id INTEGER NOT NULL, rater_id TEXT NOT NULL, target_id TEXT NOT NULL, category TEXT NOT NULL, stars INTEGER, updated_at INTEGER NOT NULL, PRIMARY KEY(round_id, rater_id, target_id, category))")
            s.executeUpdate("CREATE TABLE IF NOT EXISTS buddies(discord_id TEXT PRIMARY KEY, buddy_id TEXT NOT NULL)")
            s.executeUpdate("CREATE TABLE IF NOT EXISTS nations(id TEXT PRIMARY KEY, name TEXT NOT NULL, color INTEGER NOT NULL, role_id TEXT, category_id TEXT, channels TEXT, leader_id TEXT)")
        }
    }

    @Synchronized fun upsertUser(u: User) = c.prepareStatement("""INSERT INTO users(discord_id, mc_uuid, mc_name, nickname, show_name, funny_opt_in, availability, weekly_band, registered_at, linked_at, nation, role)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(discord_id) DO UPDATE SET mc_uuid=excluded.mc_uuid, mc_name=excluded.mc_name, nickname=excluded.nickname,
        show_name=excluded.show_name, funny_opt_in=excluded.funny_opt_in, availability=excluded.availability, weekly_band=excluded.weekly_band,
        linked_at=excluded.linked_at, nation=excluded.nation, role=excluded.role""").use { p ->
        p.setString(1, u.discordId); p.setString(2, u.mcUuid); p.setString(3, u.mcName); p.setString(4, u.nickname)
        p.setInt(5, if (u.showName) 1 else 0); p.setInt(6, if (u.funnyOptIn) 1 else 0); p.setString(7, u.availability); p.setString(8, u.weeklyBand)
        p.setLong(9, u.registeredAt); if (u.linkedAt == null) p.setNull(10, java.sql.Types.INTEGER) else p.setLong(10, u.linkedAt)
        p.setString(11, u.nation); p.setString(12, u.role)
        p.executeUpdate()
    }

    private fun readUsers(where: String, vararg args: String): List<User> = synchronized(this) {
        c.prepareStatement("SELECT discord_id, mc_uuid, mc_name, nickname, show_name, funny_opt_in, availability, weekly_band, registered_at, linked_at, nation, role FROM users $where").use { p ->
            args.forEachIndexed { i, a -> p.setString(i + 1, a) }
            p.executeQuery().use { r -> buildList { while (r.next()) add(User(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getInt(5) == 1, r.getInt(6) == 1,
                r.getString(7), r.getString(8), r.getLong(9), r.getLong(10).takeIf { !r.wasNull() }, r.getString(11), r.getString(12))) } }
        }
    }

    fun user(discordId: String): User? = readUsers("WHERE discord_id=?", discordId).firstOrNull()
    fun userByUuid(uuid: String): User? = readUsers("WHERE mc_uuid=?", uuid).firstOrNull()
    fun userByNickname(nick: String): User? = readUsers("WHERE nickname=?", nick).firstOrNull()
    fun linkedUsers(): List<User> = readUsers("WHERE linked_at IS NOT NULL")
    fun allUsers(): List<User> = readUsers("")

    @Synchronized fun putCode(code: String, uuid: String, name: String?, expiresAt: Long) = c.prepareStatement(
        "INSERT INTO link_codes(code, mc_uuid, mc_name, expires_at) VALUES(?,?,?,?) ON CONFLICT(code) DO UPDATE SET mc_uuid=excluded.mc_uuid, mc_name=excluded.mc_name, expires_at=excluded.expires_at").use { p ->
        p.setString(1, code); p.setString(2, uuid); p.setString(3, name); p.setLong(4, expiresAt); p.executeUpdate()
    }

    /** 코드를 쓴다(있고 만료 전이면 UUID, 쓰면 지운다). */
    @Synchronized fun redeemCode(code: String, now: Long): Pair<String, String?>? {
        c.prepareStatement("DELETE FROM link_codes WHERE expires_at <= ?").use { it.setLong(1, now); it.executeUpdate() }
        val row = c.prepareStatement("SELECT mc_uuid, mc_name FROM link_codes WHERE code=?").use { p -> p.setString(1, code.trim()); p.executeQuery().use { r -> if (r.next()) r.getString(1) to r.getString(2) else null } } ?: return null
        c.prepareStatement("DELETE FROM link_codes WHERE code=?").use { it.setString(1, code.trim()); it.executeUpdate() }
        return row
    }

    /** 사건 기록(중복 id 는 무시). 처음이면 true. */
    @Synchronized fun recordEvent(id: String, type: String, payload: String, ts: Long): Boolean = c.prepareStatement("INSERT OR IGNORE INTO events(id, type, payload, ts) VALUES(?,?,?,?)").use { p ->
        p.setString(1, id); p.setString(2, type); p.setString(3, payload); p.setLong(4, ts); p.executeUpdate() == 1
    }

    fun events(from: Long, until: Long): List<Triple<String, String, Long>> = synchronized(this) {
        c.prepareStatement("SELECT type, payload, ts FROM events WHERE ts >= ? AND ts < ? ORDER BY ts").use { p ->
            p.setLong(1, from); p.setLong(2, until)
            p.executeQuery().use { r -> buildList { while (r.next()) add(Triple(r.getString(1), r.getString(2), r.getLong(3))) } }
        }
    }

    /** 시간대별 접속자 수: 그 시간의 최대 동시 접속을 남긴다. */
    @Synchronized fun noteOnline(date: String, hour: Int, count: Int) = c.prepareStatement(
        "INSERT INTO activity_hourly(date, hour, online_count) VALUES(?,?,?) ON CONFLICT(date, hour) DO UPDATE SET online_count=MAX(online_count, excluded.online_count)").use { p ->
        p.setString(1, date); p.setInt(2, hour); p.setInt(3, count); p.executeUpdate()
    }

    fun onlineAt(date: String, hour: Int): Int? = synchronized(this) {
        c.prepareStatement("SELECT online_count FROM activity_hourly WHERE date=? AND hour=?").use { p -> p.setString(1, date); p.setInt(2, hour); p.executeQuery().use { r -> if (r.next()) r.getInt(1) else null } }
    }

    @Synchronized fun put(k: String, v: String) = c.prepareStatement("INSERT INTO kv(k, v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v").use { it.setString(1, k); it.setString(2, v); it.executeUpdate() }
    fun get(k: String): String? = synchronized(this) { c.prepareStatement("SELECT v FROM kv WHERE k=?").use { p -> p.setString(1, k); p.executeQuery().use { r -> if (r.next()) r.getString(1) else null } } }

    override fun close() = c.close()

    // ------------------------------------------------------------------ 상호평가
    data class Round(val id: Long, val status: String, val categories: List<String>, val startedAt: Long, val deadline: Long, val reminded: Boolean)

    @Synchronized fun newRound(categories: List<String>, start: Long, deadline: Long): Long = c.prepareStatement(
        "INSERT INTO rating_rounds(status, categories, started_at, deadline) VALUES('open',?,?,?)", java.sql.Statement.RETURN_GENERATED_KEYS).use { p ->
        p.setString(1, categories.joinToString(",")); p.setLong(2, start); p.setLong(3, deadline); p.executeUpdate()
        p.generatedKeys.use { it.next(); it.getLong(1) }
    }

    private fun readRounds(where: String): List<Round> = synchronized(this) {
        c.createStatement().use { st -> st.executeQuery("SELECT id, status, categories, started_at, deadline, reminded FROM rating_rounds $where ORDER BY id DESC").use { r ->
            buildList { while (r.next()) add(Round(r.getLong(1), r.getString(2), r.getString(3).split(',').filter { it.isNotBlank() }, r.getLong(4), r.getLong(5), r.getInt(6) == 1)) } } }
    }
    fun openRound(): Round? = readRounds("WHERE status='open'").firstOrNull()
    fun lastClosedRound(): Round? = readRounds("WHERE status='closed'").firstOrNull()
    @Synchronized fun setRoundStatus(id: Long, status: String) = c.prepareStatement("UPDATE rating_rounds SET status=? WHERE id=?").use { it.setString(1, status); it.setLong(2, id); it.executeUpdate() }
    @Synchronized fun markReminded(id: Long) = c.prepareStatement("UPDATE rating_rounds SET reminded=1 WHERE id=?").use { it.setLong(1, id); it.executeUpdate() }

    @Synchronized fun putRating(round: Long, rater: String, target: String, scores: Map<String, Int?>, now: Long) {
        for ((cat, v) in scores) c.prepareStatement("INSERT INTO ratings(round_id, rater_id, target_id, category, stars, updated_at) VALUES(?,?,?,?,?,?) " +
            "ON CONFLICT(round_id, rater_id, target_id, category) DO UPDATE SET stars=excluded.stars, updated_at=excluded.updated_at").use { p ->
            p.setLong(1, round); p.setString(2, rater); p.setString(3, target); p.setString(4, cat)
            if (v == null) p.setNull(5, java.sql.Types.INTEGER) else p.setInt(5, v); p.setLong(6, now); p.executeUpdate()
        }
    }

    /** 라운드의 평가 원자료(rater, target, 항목 → 별). 화면에는 내보내지 않는다. */
    fun ratings(round: Long): List<dev.menonng.snw.logic.discord.PeerRating.Rating> = synchronized(this) {
        val m = LinkedHashMap<Pair<String, String>, MutableMap<String, Int?>>()
        c.prepareStatement("SELECT rater_id, target_id, category, stars FROM ratings WHERE round_id=?").use { p -> p.setLong(1, round); p.executeQuery().use { r ->
            while (r.next()) { val v = r.getInt(4); m.getOrPut(r.getString(1) to r.getString(2)) { linkedMapOf() }[r.getString(3)] = if (r.wasNull()) null else v } } }
        m.map { (k, v) -> dev.menonng.snw.logic.discord.PeerRating.Rating(k.first, k.second, v) }
    }

    fun ratedTargets(round: Long, rater: String): Set<String> = synchronized(this) {
        c.prepareStatement("SELECT DISTINCT target_id FROM ratings WHERE round_id=? AND rater_id=?").use { p -> p.setLong(1, round); p.setString(2, rater)
            p.executeQuery().use { r -> buildSet { while (r.next()) add(r.getString(1)) } } }
    }

    // ------------------------------------------------------------------ 단짝·국가
    @Synchronized fun setBuddy(id: String, buddy: String) = c.prepareStatement("INSERT INTO buddies(discord_id, buddy_id) VALUES(?,?) ON CONFLICT(discord_id) DO UPDATE SET buddy_id=excluded.buddy_id").use {
        it.setString(1, id); it.setString(2, buddy); it.executeUpdate() }
    fun buddies(): List<Pair<String, String>> = synchronized(this) { c.createStatement().use { st -> st.executeQuery("SELECT discord_id, buddy_id FROM buddies").use { r -> buildList { while (r.next()) add(r.getString(1) to r.getString(2)) } } } }

    data class Nation(val id: String, val name: String, val color: Int, val roleId: String?, val categoryId: String?, val channels: String?, val leaderId: String?)
    @Synchronized fun putNation(n: Nation) = c.prepareStatement("INSERT INTO nations(id, name, color, role_id, category_id, channels, leader_id) VALUES(?,?,?,?,?,?,?) " +
        "ON CONFLICT(id) DO UPDATE SET name=excluded.name, color=excluded.color, role_id=excluded.role_id, category_id=excluded.category_id, channels=excluded.channels, leader_id=excluded.leader_id").use { p ->
        p.setString(1, n.id); p.setString(2, n.name); p.setInt(3, n.color); p.setString(4, n.roleId); p.setString(5, n.categoryId); p.setString(6, n.channels); p.setString(7, n.leaderId); p.executeUpdate() }
    fun nations(): List<Nation> = synchronized(this) { c.createStatement().use { st -> st.executeQuery("SELECT id, name, color, role_id, category_id, channels, leader_id FROM nations ORDER BY id").use { r ->
        buildList { while (r.next()) add(Nation(r.getString(1), r.getString(2), r.getInt(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7))) } } } }
}
