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
        // 시즌 전에는 캠프라 피해가 없으므로, 보호 규칙 검사 동안만 시즌이 시작된 것으로 둔다
        val season = dev.menonng.snw.season.Season.state
        val wasStarted = season.started
        season.started = true
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
        f += coreChecks(server, pa, pb, pc)
        season.started = false
        val hp0 = pa.health; pa.hurtServer(level, level.damageSources().generic(), 3f)
        if (pa.health < hp0) f += "시즌 전인데 피해를 받음"
        season.started = wasStarted
        // 개인 스폰 지점: 국가 스폰 지점에서 40~60블록(48번 안에 못 찾으면 스폰 지점)
        val n2 = reg.nation("n2")!!
        val (ox, oz) = n2.spawnX to n2.spawnZ
        n2.spawnX = 7000; n2.spawnZ = 7000
        val spot = dev.menonng.snw.season.Season.pickPersonal(server, n2, pa.uuid)
        val dist = spot?.let { Math.hypot(it.x - 7000.0, it.z - 7000.0) } ?: -1.0
        if (spot == null || !(dist in 39.0..61.0 || dist < 1.0)) f += "개인 스폰 지점이 40~60블록 고리가 아님: $dist"
        dev.menonng.snw.season.Season.state.personalSpawn.remove(pa.uuid.toString())
        n2.spawnX = ox; n2.spawnZ = oz
        // 닉네임: 개인정보 검사(거절), 통과면 표시 이름이 닉네임
        val nk = dev.menonng.snw.nickname.Nicknames
        if (nk.check(server, pa.uuid, "민수0101").verdict != dev.menonng.snw.logic.nickname.NicknameValidator.Verdict.REJECT) f += "전화번호 같은 닉네임이 거절되지 않음"
        nk.apply(null, pa.uuid, "노을빛")
        if (pa.displayName.string.contains("노을빛").not()) f += "표시 이름이 닉네임이 아님: ${pa.displayName.string}"
        nk.apply(null, pa.uuid, null)
        if (pa.displayName.string.contains("노을빛")) f += "닉네임을 지워도 표시 이름에 남음"
        // 국고: 저장·복원, 칸 해금
        val tr = dev.menonng.snw.nation.Treasury
        val box = tr.container(server, "n3")
        val keep = box.getItem(0).copy()
        box.setItem(0, net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD, 7))
        tr.save(server); box.setItem(0, net.minecraft.world.item.ItemStack.EMPTY); tr.load(server)
        if (tr.container(server, "n3").getItem(0).count != 7) f += "국고가 저장·복원되지 않음"
        tr.container(server, "n3").setItem(0, keep); tr.save(server)
        if (tr.unlocked("nX") != 27) f += "국고 기본 칸이 27 이 아님"
        // 버튼을 구분하는 창: 우클릭이 1 로 전달되는지
        var got = -1
        val menu = dev.menonng.snw.ui.Gui.Menu(1, pb.inventory, net.minecraft.world.SimpleContainer(54), mapOf(20 to { _: net.minecraft.server.level.ServerPlayer, b: Int -> got = b }))
        menu.clicked(20, 1, net.minecraft.world.inventory.ContainerInput.PICKUP, pb)
        if (got != 1) f += "창의 우클릭 버튼이 전달되지 않음: $got"
        // 거점: 지정·점령 금지 청크·소유·영토 상한 +8
        val lm = dev.menonng.snw.landmark.Landmarks
        val lpos = net.minecraft.core.BlockPos(8000, 120, 8000); level.getChunk(lpos)
        val l = lm.define(level, "smoke_lm", "시험 거점", dev.menonng.snw.landmark.Landmarks.Kind.BORDER, lpos)
        if (dev.menonng.snw.logic.territory.Chunk(8000 shr 4, 8000 shr 4) !in dev.menonng.snw.nation.NationCommands.forbidden("minecraft:overworld")) f += "거점 청크가 점령 금지가 아님"
        val capBefore = dev.menonng.snw.nation.NationCommands.cap(reg.nation("n1")!!)
        lm.setOwner(server, l, "n1")
        if (dev.menonng.snw.nation.NationCommands.cap(reg.nation("n1")!!) != capBefore + 8) f += "거점 소유가 영토 상한 +8 이 아님"
        if (!level.getBlockState(lpos).`is`(net.minecraft.world.level.block.Blocks.CHEST)) f += "거점 보상 상자가 없음"
        lm.state.landmarks.remove("smoke_lm"); level.setBlockAndUpdate(lpos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()); lm.save(server)
        for (c in listOf("season", "transfer", "nickname", "landmark", "war")) if (root.getChild("snw")?.getChild(c) == null) f += "/snw $c 미등록"
        for (c in listOf("choose", "capital", "transfer")) if (root.getChild("n")?.getChild(c) == null) f += "/n $c 미등록"
        for (p in listOf(pa, pb, pc)) reg.assign(p.uuid, null)
        dev.menonng.snw.nation.Nations.save(server)
        if (!java.nio.file.Files.exists(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("snw/nations.json"))) f += "nations.json 저장 안 됨"
        return f
    }

    /** 국가 코어: 구조물·판정, A안·내구도, 공성 시간·적대·보호, 몬스터 폭발, 멸망 이전. 끝나면 상태를 되돌린다. */
    private fun coreChecks(server: MinecraftServer, pa: FakePlayer, pb: FakePlayer, pc: FakePlayer): List<String> {
        val f = mutableListOf<String>()
        val reg = dev.menonng.snw.nation.Nations.registry
        val snap = reg.snapshot()
        val level = server.overworld()
        val pos = net.minecraft.core.BlockPos(6000, 120, 6000)
        level.getChunk(pos)
        val core = dev.menonng.snw.core.CoreSystem
        val n1 = reg.nation("n1")!!
        core.setCore(level, n1, pos)
        if (!level.getBlockState(pos).`is`(net.minecraft.world.level.block.Blocks.BEACON)) f += "코어 신호기가 놓이지 않음"
        if (level.getEntitiesOfClass(net.minecraft.world.entity.Interaction::class.java, net.minecraft.world.phys.AABB(pos).inflate(1.0)).isEmpty()) f += "코어 판정 엔티티 없음"
        reg.assign(pa.uuid, "n1"); reg.assign(pb.uuid, "n2")  // pc 는 앞 검사에서 보호 중
        val sword = net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD)
        val weapon = dev.menonng.snw.logic.core.NationCore.Weapon.SWORD
        val siege = dev.menonng.snw.core.SiegeSchedule
        // 중립이면 0
        siege.forced = true
        if (core.playerHit(pb as net.minecraft.server.level.ServerPlayer, n1, weapon, 6.0, 1f, sword) != 0.0) f += "중립 국가가 코어를 깎음"
        reg.force("n1", "n2", dev.menonng.snw.api.Relation.HOSTILE)
        Thread.sleep(600)
        val d0 = sword.damageValue
        val hit = core.playerHit(pb, n1, weapon, 6.0, 1f, sword)
        if (hit != 1.0) f += "적대 + 공성 시간 + 쿨다운 찬 검이 빈집 절반 1 이 아님: $hit"   // 가짜 플레이어는 접속자로 세지 않아 빈집
        if (sword.damageValue - d0 != 2) f += "센 공격 내구도 소모가 2 가 아님: ${sword.damageValue - d0}"
        val d1 = sword.damageValue
        if (core.playerHit(pb, n1, weapon, 6.0, 0.3f, sword) != 0.0) f += "덜 찬 공격이 들어감"
        if (sword.damageValue - d1 != 1) f += "덜 찬 공격 내구도 소모가 1 이 아님"
        siege.forced = false; Thread.sleep(600)
        if (core.playerHit(pb, n1, weapon, 6.0, 1f, sword) != 0.0) f += "공성 시간 밖인데 들어감"
        siege.forced = true; Thread.sleep(600)
        // 몬스터 폭발은 시간과 상관없이 2(빈집이면 1: pa 는 FakePlayer 라 접속자로 안 셈)
        val hp0 = reg.coreHp(n1, System.currentTimeMillis())
        core.onExplosion(level, net.minecraft.world.phys.Vec3(6000.5, 121.0, 6000.5), 3f, null)
        if (reg.coreHp(n1, System.currentTimeMillis()) >= hp0) f += "폭발이 코어를 깎지 않음"
        // 멸망: 체력 2 → 막타 국가로 국민 이전
        n1.coreHp = 1.0; n1.coreHpAtLastHit = 1.0; n1.coreLastHit = System.currentTimeMillis()
        core.playerHit(pb, n1, weapon, 6.0, 1f, sword)
        if (!n1.fallen) f += "코어 0 인데 멸망하지 않음"
        if (reg.nationOf(pa.uuid)?.id != "n2") f += "멸망한 국가 국민이 막타 국가로 옮겨지지 않음: ${reg.nationOf(pa.uuid)?.id}"
        if (level.getBlockState(pos).`is`(net.minecraft.world.level.block.Blocks.BEACON)) f += "멸망 뒤 코어가 남음"
        siege.forced = null
        reg.restore(snap)
        dev.menonng.snw.nation.Nations.syncTeams(server)
        return f
    }

    /** Fabric 의 FakePlayer 는 getTeam() 이 늘 null 이라, 실제 플레이어처럼 팀을 돌려주게 한다. */
    private class SmokePlayer(level: ServerLevel, name: String = "snw_smoke") : FakePlayer(level, GameProfile(UUID.nameUUIDFromBytes(name.toByteArray()), name)) {
        override fun getTeam(): PlayerTeam? = level().scoreboard.getPlayersTeam(scoreboardName)
        /** Fabric FakePlayer 는 모든 피해를 무시하므로, 보호 규칙 검사가 헛돌지 않게 실제로 피해를 받게 한다. */
        override fun isInvulnerableTo(level: ServerLevel, source: net.minecraft.world.damagesource.DamageSource): Boolean = false
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
