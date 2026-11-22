package dev.menonng.snw

import dev.menonng.snw.chat.ChatRouting
import dev.menonng.snw.logic.chat.ChatRoute
import dev.menonng.snw.mixin.TeamMsgCommandInvoker
import com.mojang.authlib.GameProfile
import net.fabricmc.fabric.api.entity.FakePlayer
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.PlayerChatMessage
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.commands.TeamMsgCommand
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.scores.PlayerTeam
import java.util.UUID

/**
 * `-Dsnw.smoke=true` 일 때만 도는 서버 기동 확인. CI 와 `./gradlew :mod:runServer` 가 쓴다.
 * 가짜 플레이어로 채팅 경로(기본 = 국가, /g = 전체)가 실제 이벤트 흐름에서 맞게 갈리는지도 본다.
 */
internal object Smoke {
    private var reachedAfterDefault = false

    fun run(server: MinecraftServer): List<String> {
        val failures = mutableListOf<String>()
        if (!ChatRouting.commandsRegistered(server)) failures += "/g·/전체 명령 미등록"
        if (!TeamMsgCommandInvoker::class.java.isAssignableFrom(TeamMsgCommand::class.java)) failures += "TeamMsgCommand 믹스인 미적용"
        failures += nationChecks(server)

        // 기본 단계(couriersRoost 의 전체 채팅 처리) 뒤에 도는 탐침. 앞 단계에서 취소되면 불리지 않는다.
        val probe = Identifier.fromNamespaceAndPath(Snw.MOD_ID, "smoke_probe")
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.addPhaseOrdering(Event.DEFAULT_PHASE, probe)
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(probe, ServerMessageEvents.AllowChatMessage { _, _, _ -> reachedAfterDefault = true; true })
        val courier = FabricLoader.getInstance().isModLoaded("courierpost")

        val fake = SmokePlayer(server.overworld())
        val scoreboard = server.scoreboard
        val team = scoreboard.addPlayerTeam("snw_smoke")
        try {
            // 국가 없음 → 전체
            chat(server, fake, "smoke-no-team")
            if (ChatRouting.lastRoute != ChatRoute.GLOBAL) failures += "국가 없는 채팅이 전체로 가지 않음: ${ChatRouting.lastRoute}"

            scoreboard.addPlayerToTeam(fake.scoreboardName, team)
            // 국가 있음, 명령어 없이 → 국가 채팅, 뒤 단계(전체 처리)까지 가면 안 된다
            reachedAfterDefault = false
            chat(server, fake, "smoke-team")
            if (ChatRouting.lastRoute != ChatRoute.TEAM) failures += "기본 채팅이 국가 채팅으로 가지 않음: ${ChatRouting.lastRoute}"
            if (reachedAfterDefault) failures += "국가 채팅이 전체 채팅 단계까지 넘어감"

            // /g → 전체. couriersRoost 가 없으면 바닐라 방송까지, 있으면 전서구가 받아 취소한다
            reachedAfterDefault = false
            server.commands.performPrefixedCommand(fake.createCommandSourceStack(), "g smoke-global")
            if (ChatRouting.lastRoute != ChatRoute.GLOBAL) failures += "/g 가 전체 채팅으로 가지 않음: ${ChatRouting.lastRoute}"
            if (!courier && !reachedAfterDefault) failures += "/g 가 바닐라 전체 방송 단계에 닿지 않음"
            if (courier && reachedAfterDefault) failures += "/g 를 couriersRoost 가 받지 않음"
        } finally {
            scoreboard.removePlayerTeam(team)
        }
        failures += infoChecks(server)
        Snw.log.info("[simpleNationWar] smoke: couriersRoost={}, 채팅 경로 확인 {}", courier, if (failures.isEmpty()) "통과" else "실패")
        return failures
    }

    /** 국가: 기본 세 국가와 바닐라 팀, 명령 등록, API 설치, 영토·관계 왕복, 저장. */
    private fun nationChecks(server: MinecraftServer): List<String> {
        val f = mutableListOf<String>()
        val reg = dev.menonng.snw.nation.Nations.registry
        if (reg.nations.keys.toSet() != setOf("n1", "n2", "n3")) f += "기본 국가 3개가 아님: ${reg.nations.keys}"
        for (id in reg.nations.keys) if (server.scoreboard.getPlayerTeam("snw_$id")?.isAllowFriendlyFire != true) f += "팀 snw_$id 없음 또는 아군 피해 꺼짐"
        val root = server.commands.dispatcher.root
        for (c in listOf("snw", "n", "국가")) if (root.getChild(c) == null) f += "/$c 미등록"
        val api = dev.menonng.snw.api.NationApi.getOrNull()
        if (api == null) f += "NationApi 미설치"
        val k = dev.menonng.snw.api.ChunkKey("minecraft:overworld", 9999, 9999)
        dev.menonng.snw.nation.Nations.claimAndNotify("n2", listOf(k))
        if (api?.ownerOf(k)?.id?.value != "n2") f += "영토 점령이 API 에 보이지 않음"
        dev.menonng.snw.nation.Nations.unclaimAndNotify(listOf(k))
        if (api?.ownerOf(k) != null) f += "영토 해제가 API 에 보이지 않음"
        // 같은 국가에게 치명타 → 그 자리 부활, 동맹은 피해 없음
        val level = server.overworld()
        val pa = SmokePlayer(level, "snw_smoke_a"); val pb = SmokePlayer(level, "snw_smoke_b"); val pc = SmokePlayer(level, "snw_smoke_c")
        for (p in listOf(pa, pb, pc)) { p.snapTo(5000.0, 100.0, 5000.0, 0f, 0f) }
        reg.assign(pa.uuid, "n1"); reg.assign(pb.uuid, "n1"); reg.assign(pc.uuid, "n3")
        reg.force("n1", "n3", dev.menonng.snw.api.Relation.ALLIED)
        pa.hurtServer(level, level.damageSources().playerAttack(pb), 1000f)
        if (!pa.isAlive || pa.health < pa.maxHealth) f += "같은 국가 사망이 그 자리 부활로 처리되지 않음(체력 ${pa.health})"
        val before = pa.health
        pa.hurtServer(level, level.damageSources().playerAttack(pc), 5f)
        if (pa.health < before) f += "동맹에게 피해를 받음"
        reg.force("n1", "n3", dev.menonng.snw.api.Relation.NEUTRAL)
        dev.menonng.snw.protect.Protection.markProtected(pc.uuid, System.currentTimeMillis() + 10_000)
        pa.hurtServer(level, level.damageSources().playerAttack(pc), 5f)
        if (pa.health < before) f += "보호 중인 공격자의 피해가 들어감"
        for (p in listOf(pa, pb, pc)) reg.assign(p.uuid, null)
        dev.menonng.snw.nation.Nations.save(server)
        if (!java.nio.file.Files.exists(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("snw/nations.json"))) f += "nations.json 저장 안 됨"
        return f
    }

    /** Fabric 의 FakePlayer 는 getTeam() 이 늘 null 이라, 실제 플레이어처럼 팀을 돌려주게 한다. */
    private class SmokePlayer(level: ServerLevel, name: String = "snw_smoke") : FakePlayer(level, GameProfile(UUID.nameUUIDFromBytes(name.toByteArray()), name)) {
        override fun getTeam(): PlayerTeam? = level().scoreboard.getPlayersTeam(scoreboardName)
    }

    private fun chat(server: MinecraftServer, player: FakePlayer, text: String) {
        server.playerList.broadcastChatMessage(PlayerChatMessage.system(text), player, ChatType.bind(ChatType.CHAT, player))
    }

    /** /info 상자 창: 명령 등록, 클릭하면 동작만 하고 아이템은 빠지지 않는지. */
    private fun infoChecks(server: MinecraftServer): List<String> {
        val f = mutableListOf<String>()
        val root = server.commands.dispatcher.root
        if (root.getChild("info") == null || root.getChild("정보") == null) f += "/info·/정보 명령 미등록"
        val fake = net.fabricmc.fabric.api.entity.FakePlayer.get(server.overworld())
        val container = net.minecraft.world.SimpleContainer(54)
        container.setItem(10, net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PLAYER_HEAD))
        var clicked = 0
        val menu = dev.menonng.snw.info.InfoMenus.ReadOnlyMenu(1, fake.inventory, container, mapOf(10 to { _: net.minecraft.server.level.ServerPlayer -> clicked++ }))
        menu.clicked(10, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, fake)
        menu.clicked(10, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, fake)
        menu.clicked(10, 0, net.minecraft.world.inventory.ContainerInput.THROW, fake)
        menu.clicked(10, 1, net.minecraft.world.inventory.ContainerInput.SWAP, fake)
        if (clicked != 2) f += "/info 칸 클릭 동작 횟수 오류: $clicked"
        if (container.getItem(10).isEmpty) f += "/info 창에서 아이템이 빠짐"
        if (!menu.carried.isEmpty) f += "/info 창에서 아이템을 집음"
        if (fake.inventory.contains { it.`is`(net.minecraft.world.item.Items.PLAYER_HEAD) }) f += "/info 창의 아이템이 인벤토리로 옮겨짐"
        val entries = dev.menonng.snw.info.InfoMenus.entries(server)
        Snw.log.info("[simpleNationWar] smoke: /info 목록 {}명", entries.size)
        return f
    }
}
