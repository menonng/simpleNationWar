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
}
