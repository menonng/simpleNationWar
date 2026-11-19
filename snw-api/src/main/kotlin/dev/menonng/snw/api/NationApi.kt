package dev.menonng.snw.api

import java.util.UUID

/**
 * 다른 모드(zomApo, snw-discord)가 simpleNationWar 를 읽고 쓰는 창구.
 * 마인크래프트 타입을 쓰지 않으므로 compileOnly 로 가볍게 참조할 수 있다.
 * 실행 시에는 FabricLoader.isModLoaded("snw") 를 확인한 뒤 [NationApi.get] 을 부른다.
 */
interface NationApi {
    fun nations(): List<NationView>
    fun nationOf(player: UUID): NationView?
    fun sameNation(a: UUID, b: UUID): Boolean
    fun allied(a: UUID, b: UUID): Boolean
    fun ownerOf(chunk: ChunkKey): NationView?
    fun territory(nation: NationId): Set<ChunkKey>
    fun nickname(player: UUID): String?
    fun setNickname(player: UUID, nickname: String?)
    fun assign(player: UUID, nation: NationId?, role: Role)
    fun events(): NationEvents
    fun eventBus(): ServerEventBus

    companion object {
        @Volatile
        private var instance: NationApi? = null

        fun get(): NationApi = checkNotNull(instance) { "simpleNationWar 가 아직 초기화되지 않았다" }
        fun getOrNull(): NationApi? = instance

        /** simpleNationWar 본체만 부른다. */
        fun install(api: NationApi) {
            instance = api
        }
    }
}

@JvmInline
value class NationId(val value: String)

/** 청크 위치. 차원은 "minecraft:overworld" 같은 문자열 id. */
data class ChunkKey(val dimension: String, val x: Int, val z: Int)

enum class Role { LEADER, OFFICER, CITIZEN }

enum class Relation { NEUTRAL, ALLIED, HOSTILE }

/** 읽기 전용 스냅샷. */
data class NationView(
    val id: NationId,
    val name: String,
    val colorRgb: Int,
    val leader: UUID?,
    val members: Set<UUID>,
    val capital: ChunkKey?,
)

interface NationEvents {
    fun onClaimChanged(listener: (nation: NationId, added: Set<ChunkKey>, removed: Set<ChunkKey>) -> Unit)
    fun onRelationChanged(listener: (a: NationId, b: NationId, from: Relation, to: Relation) -> Unit)
    fun onPlayerKill(listener: (KillRecord) -> Unit)
}

data class KillRecord(
    val victim: UUID,
    val killer: UUID?,
    val assists: List<UUID>,
    val pvp: Boolean,
    val countedAsKill: Boolean,
    val epochMillis: Long,
)

/** `/info`, 국가 GUI 국민 목록에 줄을 끼워 넣는 확장점. 엔트리포인트 "snw:stat_provider". */
interface StatProvider {
    fun lines(player: UUID, viewer: UUID?): List<StatLine>
}

data class StatLine(val label: String, val value: String, val hover: String? = null)

/**
 * 모드 사이에 게임 사건을 전달한다. 디스코드 모드가 구독해서 봇으로 보낸다.
 * [ServerEvent.data] 에는 좌표·채팅 같은 개인정보를 넣지 않는다(docs/server/privacy.md).
 */
interface ServerEventBus {
    fun publish(event: ServerEvent)
    fun subscribe(type: String?, listener: (ServerEvent) -> Unit)
}

data class ServerEvent(val type: String, val epochMillis: Long, val data: Map<String, Any?>)
