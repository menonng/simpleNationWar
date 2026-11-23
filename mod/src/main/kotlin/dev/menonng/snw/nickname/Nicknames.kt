package dev.menonng.snw.nickname

import com.google.gson.GsonBuilder
import dev.menonng.snw.logic.nickname.NicknameValidator
import dev.menonng.snw.nation.Nations
import dev.menonng.snw.protect.Protection
import dev.menonng.snw.season.Season
import dev.menonng.snw.ui.Gui
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.util.EnumSet
import java.util.UUID

/**
 * 커스텀 닉네임(nickname.md). 원본은 디스코드 봇 DB 이고, 서버는 world/snw/nicknames.json 에 캐시한다.
 * - 표시: 탭 목록 `[국가] 닉네임`, 채팅·사망 메시지(표시 이름), /info `닉네임 (마크이름)`. 머리 위 이름표는 바닐라 구조상 마크 이름 그대로.
 * - 등록·변경: 봇(snw-api setNickname) 또는 운영자 `/snw nickname`, 국민 `/n nickname`(시즌 전 자유, 시즌 중에는 운영자 승인 1회).
 * - 검사: NicknameValidator(거절 / 운영자 확인 / 통과), 금지어 config/snw-banned-words.txt.
 */
object Nicknames {
    data class Pending(val nick: String, val reasons: List<String>)
    data class State(
        val nicks: MutableMap<String, String> = linkedMapOf(),
        val seasonChanges: MutableMap<String, Int> = linkedMapOf(),
        val pending: MutableMap<String, Pending> = linkedMapOf(),
    )

    var state = State()
        private set
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private fun file(s: MinecraftServer) = s.getWorldPath(LevelResource.ROOT).resolve("snw").resolve("nicknames.json")

    private fun banned(): Set<String> {
        val f = FabricLoader.getInstance().configDir.resolve("snw-banned-words.txt")
        if (!Files.exists(f)) runCatching { Files.writeString(f, "# 닉네임 금지어. 한 줄에 하나.\n") }
        return runCatching { Files.readAllLines(f).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet() }.getOrDefault(emptySet())
    }

    fun get(id: UUID): String? = state.nicks[id.toString()]

    fun check(s: MinecraftServer, id: UUID, raw: String): NicknameValidator.Result {
        val taken = state.nicks.filterKeys { it != id.toString() }.values.toSet()
        val mcNames = s.playerList.players.filter { it.uuid != id }.map { it.scoreboardName }.toSet() +
            Nations.registry.nations.values.flatMap { it.members }.filter { it != id }.mapNotNull { Nations.nameOf(s, it) }
        return NicknameValidator(banned()).check(raw, NicknameValidator.Context(taken, mcNames))
    }

    /** 실제로 바꾼다(검사는 부르는 쪽이 했다). null 이면 지운다. */
    fun apply(s: MinecraftServer?, id: UUID, nick: String?) {
        if (nick == null) state.nicks.remove(id.toString()) else state.nicks[id.toString()] = nick
        state.pending.remove(id.toString())
        if (Season.state.started && nick != null) state.seasonChanges.merge(id.toString(), 1, Int::plus)
        s?.let { save(it); refresh(it, id) }
    }

    /** 탭 목록 이름을 다시 보낸다. */
    fun refresh(s: MinecraftServer, id: UUID) {
        val p = s.playerList.getPlayer(id) ?: return
        s.playerList.broadcastAll(ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME), listOf(p)))
    }

    /** 국민이 직접: 시즌 전 자유(통과면 바로, 확인이 필요하면 운영자 승인 대기), 시즌 중에는 늘 운영자 승인(1회). */
    fun request(p: ServerPlayer, raw: String): String {
        val s = p.level().server
        val r = check(s, p.uuid, raw)
        if (r.verdict == NicknameValidator.Verdict.REJECT) return "쓸 수 없는 닉네임입니다: ${r.reasons.joinToString { it.message }}."
        val inSeason = Season.state.started
        if (inSeason && (state.seasonChanges[p.uuid.toString()] ?: 0) >= 1) return "시즌 중 닉네임 변경은 한 번만 할 수 있습니다."
        if (!inSeason && r.verdict == NicknameValidator.Verdict.ACCEPT) { apply(s, p.uuid, r.normalized); return "닉네임을 '${r.normalized}'(으)로 정했습니다." }
        state.pending[p.uuid.toString()] = Pending(r.normalized, r.reasons.map { it.message })
        save(s)
        for (op in s.playerList.players.filter { net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(it.permissions()) })
            op.sendSystemMessage(Gui.text("닉네임 승인 요청: ${p.scoreboardName} → ${r.normalized}${if (r.reasons.isNotEmpty()) " (${r.reasons.joinToString { it.message }})" else ""}. /snw nickname approve ${p.scoreboardName}", Gui.rgb(0xFF7E00)))
        Nations.publish("nickname_review", mapOf("nickname" to r.normalized, "reasons" to r.reasons.map { it.name }))
        return "운영자 확인이 필요합니다. 승인되면 바뀝니다."
    }

    /** 표시 이름: 닉네임이 있으면 [국가 접두사] 닉네임(팀 색). 원래 표시 이름의 마우스 올림·클릭은 그대로. */
    @JvmStatic
    fun displayName(p: Player, original: Component): Component {
        val nick = get(p.uuid) ?: return original
        val team = p.team
        val base: MutableComponent = team?.getFormattedName(Component.literal(nick)) ?: Component.literal(nick)
        return base.withStyle(original.style.withColor(base.style.color ?: original.style.color))
    }

    /** 탭 목록: 닉네임 + 잠수 표시(Protection.tabName 이 잠수 꼬리표를 붙인다). */
    @JvmStatic
    fun tabName(p: ServerPlayer, base: Component?): Component? {
        val nick = get(p.uuid)
        var named = if (nick != null) (p.team?.getFormattedName(Component.literal(nick)) ?: Component.literal(nick)) else base
        // 칭호가 있으면 이름 앞에(nickname.md 2절 '칭호'): 분홍 #FFB4CC
        dev.menonng.snw.info.InfoMenus.titles(p.uuid).firstOrNull()?.let { t ->
            val n0 = named ?: (p.team?.getFormattedName(p.name) ?: p.name.copy())
            named = Component.literal("$t ").withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(net.minecraft.network.chat.TextColor.fromRgb(0xFFB4CC))).append(n0)
        }
        return Protection.tabName(p, named)
    }

    fun load(s: MinecraftServer) { val f = file(s); if (Files.exists(f)) runCatching { state = gson.fromJson(Files.readString(f), State::class.java) } }
    fun save(s: MinecraftServer) { val f = file(s); Files.createDirectories(f.parent); Files.writeString(f, gson.toJson(state)) }

    fun register() {
        ServerLifecycleEvents.SERVER_STARTING.register { s -> load(s) }
        ServerLifecycleEvents.SERVER_STOPPING.register { s -> save(s) }
    }
}
