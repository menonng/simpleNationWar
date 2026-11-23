package dev.menonng.snw.nation

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import dev.menonng.snw.api.ChunkKey
import dev.menonng.snw.api.Relation
import dev.menonng.snw.api.Role
import dev.menonng.snw.logic.spawn.NationSpawn
import dev.menonng.snw.logic.spawn.XZ
import dev.menonng.snw.logic.state.NationRegistry
import dev.menonng.snw.logic.territory.Chunk
import dev.menonng.snw.logic.territory.TerritoryRules
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.server.level.ServerLevel

/**
 * 운영자 `/snw` 와 일반 `/n` 의 국가·영토·관계 명령(nation.md '명령어').
 * 알림·안내 문구는 하십시오체.
 */
object NationCommands {
    private val rules = TerritoryRules()
    /** 생존자 캠프(중앙) 점령 금지 반경(청크). */
    const val CAMP_CHUNK_RADIUS = 4

    private val nationIds = SuggestionProvider<CommandSourceStack> { _, b -> SharedSuggestionProvider.suggest(Nations.registry.nations.keys, b) }
    private val playerNames = SuggestionProvider<CommandSourceStack> { c, b -> SharedSuggestionProvider.suggest(c.source.server.playerList.playerNamesArray.toList(), b) }
    private val relationWords = SuggestionProvider<CommandSourceStack> { _, b -> SharedSuggestionProvider.suggest(listOf("ally", "neutral", "hostile", "동맹", "중립", "적대"), b) }

    fun dim(level: ServerLevel): String = level.dimension().identifier().toString()

    private fun ok(c: CommandContext<CommandSourceStack>, msg: String) { c.source.sendSuccess({ Component.literal(msg) }, true) }
    private fun fail(c: CommandContext<CommandSourceStack>, msg: String): Int { c.source.sendFailure(Component.literal(msg)); return 0 }

    private fun parseRelation(w: String): Relation? = when (w.lowercase()) {
        "ally", "allied", "동맹" -> Relation.ALLIED
        "neutral", "중립" -> Relation.NEUTRAL
        "hostile", "적대" -> Relation.HOSTILE
        else -> null
    }

    fun relationName(r: Relation) = when (r) { Relation.ALLIED -> "동맹"; Relation.NEUTRAL -> "중립"; Relation.HOSTILE -> "적대" }

    private fun nationArg(c: CommandContext<CommandSourceStack>): NationRegistry.Nation? =
        Nations.registry.nation(StringArgumentType.getString(c, "nation"))

    fun register() {
        CommandRegistrationCallback.EVENT.register { d, _, _ ->
            val admin = Commands.literal("snw").requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.literal("status").executes { status(it) })
                .then(Commands.literal("nickname").then(Commands.argument("player", StringArgumentType.word()).suggests(playerNames)
                    .then(Commands.literal("set").then(Commands.argument("nick", StringArgumentType.word()).executes { c -> adminNick(c, false) }
                        .then(Commands.literal("confirm").executes { c -> adminNick(c, true) })))
                    .then(Commands.literal("clear").executes { c ->
                        val id = Nations.idOf(c.source.server, StringArgumentType.getString(c, "player")) ?: return@executes fail(c, "그런 플레이어를 찾지 못했습니다.")
                        dev.menonng.snw.nickname.Nicknames.apply(c.source.server, id, null); ok(c, "닉네임을 지웠습니다."); 1
                    })
                    .then(Commands.literal("approve").executes { c ->
                        val id = Nations.idOf(c.source.server, StringArgumentType.getString(c, "player")) ?: return@executes fail(c, "그런 플레이어를 찾지 못했습니다.")
                        val pend = dev.menonng.snw.nickname.Nicknames.state.pending[id.toString()] ?: return@executes fail(c, "승인을 기다리는 닉네임이 없습니다.")
                        dev.menonng.snw.nickname.Nicknames.apply(c.source.server, id, pend.nick)
                        c.source.server.playerList.getPlayer(id)?.sendSystemMessage(Component.literal("닉네임 '${pend.nick}'이(가) 승인되었습니다."))
                        ok(c, "닉네임을 승인했습니다."); 1
                    })))
                .then(Commands.literal("season")
                    .then(Commands.literal("start").executes { c -> ok(c, dev.menonng.snw.season.Season.start(c.source.server)); 1 })
                    .then(Commands.literal("status").executes { c -> c.source.sendSuccess({ Component.literal(dev.menonng.snw.season.Season.status(c.source.server)) }, false); 1 }))
                .then(Commands.literal("transfer").then(Commands.literal("open").then(Commands.argument("hours", IntegerArgumentType.integer(1, 168)).executes { c ->
                    dev.menonng.snw.season.Season.openTransfer(c.source.server, IntegerArgumentType.getInteger(c, "hours")); ok(c, "이적 창을 열었습니다."); 1
                })))
                .then(Commands.literal("save").executes { Nations.save(it.source.server); ok(it, "국가 데이터를 저장했습니다."); 1 })
                .then(Commands.literal("nation").then(Commands.argument("nation", StringArgumentType.word()).suggests(nationIds)
                    .then(Commands.literal("rename").then(Commands.argument("name", StringArgumentType.greedyString()).executes { c ->
                        val n = nationArg(c) ?: return@executes fail(c, "그런 국가가 없습니다.")
                        n.name = StringArgumentType.getString(c, "name").trim().take(16); Nations.markDirty(); Nations.syncTeams(c.source.server)
                        ok(c, "${n.id} 의 이름을 '${n.name}'(으)로 바꿨습니다."); 1
                    }))
                    .then(Commands.literal("leader").then(Commands.argument("player", StringArgumentType.word()).suggests(playerNames).executes { c ->
                        val n = nationArg(c) ?: return@executes fail(c, "그런 국가가 없습니다.")
                        val id = Nations.idOf(c.source.server, StringArgumentType.getString(c, "player")) ?: return@executes fail(c, "그런 플레이어를 찾지 못했습니다.")
                        Nations.assignAndSync(id, n.id, Role.LEADER); ok(c, "${n.name} 의 원수를 지정했습니다."); 1
                    }))))
                .then(Commands.literal("member").then(Commands.argument("player", StringArgumentType.word()).suggests(playerNames)
                    .then(Commands.literal("set").then(Commands.argument("nation", StringArgumentType.word()).suggests(nationIds).executes { c ->
                        val n = nationArg(c) ?: return@executes fail(c, "그런 국가가 없습니다.")
                        val id = Nations.idOf(c.source.server, StringArgumentType.getString(c, "player")) ?: return@executes fail(c, "그런 플레이어를 찾지 못했습니다.")
                        Nations.assignAndSync(id, n.id); ok(c, "${n.name}(으)로 배정했습니다."); 1
                    }))
                    .then(Commands.literal("officer").executes { c ->
                        val id = Nations.idOf(c.source.server, StringArgumentType.getString(c, "player")) ?: return@executes fail(c, "그런 플레이어를 찾지 못했습니다.")
                        val n = Nations.registry.nationOf(id) ?: return@executes fail(c, "국가에 속해 있지 않습니다.")
                        if (n.officers.size >= NationRegistry.MAX_OFFICERS) return@executes fail(c, "간부는 두 명까지입니다.")
                        Nations.assignAndSync(id, n.id, Role.OFFICER); ok(c, "간부로 임명했습니다."); 1
                    })
                    .then(Commands.literal("clear").executes { c ->
                        val id = Nations.idOf(c.source.server, StringArgumentType.getString(c, "player")) ?: return@executes fail(c, "그런 플레이어를 찾지 못했습니다.")
                        Nations.assignAndSync(id, null); ok(c, "소속을 해제했습니다."); 1
                    })))
                .then(Commands.literal("spawn")
                    .then(Commands.literal("set").then(Commands.argument("nation", StringArgumentType.word()).suggests(nationIds)
                        .then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer()).executes { c ->
                            val n = nationArg(c) ?: return@executes fail(c, "그런 국가가 없습니다.")
                            n.spawnX = IntegerArgumentType.getInteger(c, "x"); n.spawnZ = IntegerArgumentType.getInteger(c, "z"); Nations.markDirty()
                            ok(c, "${n.name} 의 국가 스폰 지점을 지정했습니다. ${spawnProblems()}"); 1
                        }))))
                    .then(Commands.literal("check").executes { c -> ok(c, spawnProblems()); 1 }))
                .then(Commands.literal("claim").then(Commands.argument("nation", StringArgumentType.word()).suggests(nationIds)
                    .then(Commands.literal("add").executes { claimArea(it, true, 0) }
                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 8)).executes { claimArea(it, true, IntegerArgumentType.getInteger(it, "radius")) }))
                    .then(Commands.literal("remove").executes { claimArea(it, false, 0) }
                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 8)).executes { claimArea(it, false, IntegerArgumentType.getInteger(it, "radius")) }))))
                .then(Commands.literal("core").then(Commands.argument("nation", StringArgumentType.word()).suggests(nationIds)
                    .then(Commands.literal("set").executes { c ->
                        val n = nationArg(c) ?: return@executes fail(c, "그런 국가가 없습니다.")
                        val pos = net.minecraft.core.BlockPos.containing(c.source.position)
                        dev.menonng.snw.core.CoreSystem.setCore(c.source.level, n, pos)
                        ok(c, "${n.name}의 국가 코어를 놓았습니다. 그 청크가 수도가 됩니다."); 1
                    })
                    .then(Commands.literal("hp").then(Commands.argument("value", IntegerArgumentType.integer(1, 100_000)).executes { c ->
                        val n = nationArg(c) ?: return@executes fail(c, "그런 국가가 없습니다.")
                        val v = IntegerArgumentType.getInteger(c, "value").toDouble()
                        n.coreHp = v; n.coreHpAtLastHit = v; n.coreLastHit = System.currentTimeMillis(); Nations.markDirty()
                        ok(c, "${n.name} 코어 체력을 ${v.toInt()}(으)로 맞췄습니다."); 1
                    }))))
                .then(Commands.literal("siege")
                    .then(Commands.literal("open").executes { c -> dev.menonng.snw.core.SiegeSchedule.forced = true; ok(c, "공성 시간을 수동으로 열었습니다(/snw siege auto 로 되돌립니다)."); 1 })
                    .then(Commands.literal("close").executes { c -> dev.menonng.snw.core.SiegeSchedule.forced = false; ok(c, "공성 시간을 수동으로 닫았습니다."); 1 })
                    .then(Commands.literal("auto").executes { c -> dev.menonng.snw.core.SiegeSchedule.forced = null; ok(c, "공성 시간을 자동으로 되돌렸습니다."); 1 })
                    .executes { c ->
                    val st = dev.menonng.snw.core.SiegeSchedule
                    val (wd, we) = st.windows()
                    ok(c, "공성 시간: 평일 ${wd.label}, 주말 ${we.label}. 오늘 ${st.todayLabel()}${if (st.isOpen()) " (지금 열림)" else ""}. 조사한 날 ${st.state.samples.size}일"); 1
                })
                .then(Commands.literal("relation").then(Commands.argument("nation", StringArgumentType.word()).suggests(nationIds)
                    .then(Commands.argument("other", StringArgumentType.word()).suggests(nationIds)
                        .then(Commands.argument("relation", StringArgumentType.word()).suggests(relationWords).executes { c ->
                            val a = nationArg(c) ?: return@executes fail(c, "그런 국가가 없습니다.")
                            val b = Nations.registry.nation(StringArgumentType.getString(c, "other")) ?: return@executes fail(c, "그런 국가가 없습니다.")
                            val rel = parseRelation(StringArgumentType.getString(c, "relation")) ?: return@executes fail(c, "관계는 동맹·중립·적대 중 하나입니다.")
                            val before = Nations.registry.relation(a.id, b.id, System.currentTimeMillis())
                            Nations.registry.force(a.id, b.id, rel); Nations.notifyRelation(a.id, b.id, before, rel)
                            ok(c, "${a.name}·${b.name} 관계를 ${relationName(rel)}(으)로 바꿨습니다."); 1
                        }))))
            d.register(admin)

            val n = Commands.literal("n").executes { c -> dev.menonng.snw.nation.NationGui.open(c.source.playerOrException); 1 }
                .then(Commands.literal("info").executes { info(it) })
                .then(Commands.literal("claim").executes { playerClaim(it, true) })
                .then(Commands.literal("unclaim").executes { playerClaim(it, false) })
                .then(Commands.literal("relation").then(Commands.argument("nation", StringArgumentType.word()).suggests(nationIds)
                    .then(Commands.argument("relation", StringArgumentType.word()).suggests(relationWords).executes { playerRelation(it) })))
                .then(Commands.literal("nickname").then(Commands.argument("nick", StringArgumentType.word()).executes { c ->
                    ok(c, dev.menonng.snw.nickname.Nicknames.request(c.source.playerOrException, StringArgumentType.getString(c, "nick"))); 1
                }))
                .then(Commands.literal("choose").executes { c -> dev.menonng.snw.season.Season.openChooser(c.source.playerOrException); 1 })
                .then(Commands.literal("capital").then(Commands.literal("move").executes { c ->
                    val r = dev.menonng.snw.season.Season.moveCapital(c.source.playerOrException)
                    if (r.endsWith("옮겼습니다.")) { ok(c, r); 1 } else fail(c, r)
                }))
                .then(Commands.literal("transfer")
                    .then(Commands.literal("accept").then(Commands.argument("player", StringArgumentType.word()).suggests(playerNames).executes { c ->
                        ok(c, dev.menonng.snw.season.Season.acceptTransfer(c.source.playerOrException, StringArgumentType.getString(c, "player"))); 1
                    }))
                    .then(Commands.argument("nation", StringArgumentType.word()).suggests(nationIds).executes { c ->
                        val to = nationArg(c)?.takeIf { !it.fallen } ?: return@executes fail(c, "그런 국가가 없습니다.")
                        ok(c, dev.menonng.snw.season.Season.requestTransfer(c.source.playerOrException, to)); 1
                    }))
                .then(Commands.literal("officer").then(Commands.argument("player", StringArgumentType.word()).suggests(playerNames).executes { c ->
                    val me = c.source.playerOrException
                    val nat = Nations.registry.nationOf(me.uuid) ?: return@executes fail(c, "국가에 속해 있지 않습니다.")
                    if (dev.menonng.snw.season.Season.effectiveLeader(nat) != me.uuid) return@executes fail(c, "간부 임명은 원수만 할 수 있습니다.")
                    val id = Nations.idOf(c.source.server, StringArgumentType.getString(c, "player")) ?: return@executes fail(c, "그런 플레이어를 찾지 못했습니다.")
                    if (Nations.registry.nationOf(id)?.id != nat.id) return@executes fail(c, "같은 국가 국민만 임명할 수 있습니다.")
                    if (id in nat.officers) { nat.officers.remove(id); Nations.markDirty(); ok(c, "간부에서 해임했습니다."); return@executes 1 }
                    if (nat.officers.size >= NationRegistry.MAX_OFFICERS) return@executes fail(c, "간부는 두 명까지입니다.")
                    Nations.assignAndSync(id, nat.id, Role.OFFICER); ok(c, "간부로 임명했습니다."); 1
                }))
            val nRoot = d.register(n)
            d.register(Commands.literal("국가").redirect(nRoot).executes { c -> dev.menonng.snw.nation.NationGui.open(c.source.playerOrException); 1 })
        }
    }

    private fun adminNick(c: CommandContext<CommandSourceStack>, confirm: Boolean): Int {
        val s = c.source.server
        val id = Nations.idOf(s, StringArgumentType.getString(c, "player")) ?: return fail(c, "그런 플레이어를 찾지 못했습니다.")
        val r = dev.menonng.snw.nickname.Nicknames.check(s, id, StringArgumentType.getString(c, "nick"))
        if (r.verdict == dev.menonng.snw.logic.nickname.NicknameValidator.Verdict.REJECT) return fail(c, "쓸 수 없는 닉네임입니다: ${r.reasons.joinToString { it.message }}.")
        if (r.verdict == dev.menonng.snw.logic.nickname.NicknameValidator.Verdict.REVIEW && !confirm)
            return fail(c, "확인이 필요한 닉네임입니다(${r.reasons.joinToString { it.message }}). 맞으면 끝에 confirm 을 붙여 다시 입력하십시오.")
        dev.menonng.snw.nickname.Nicknames.apply(s, id, r.normalized)
        ok(c, "닉네임을 '${r.normalized}'(으)로 정했습니다."); return 1
    }

    private fun spawnProblems(): String {
        val pts = Nations.registry.nations.values.mapNotNull { n -> n.spawnX?.let { XZ(it.toDouble(), n.spawnZ!!.toDouble()) } }
        if (pts.size < 3) return "(스폰 지점 ${pts.size}/3 지정)"
        val p = NationSpawn.validate(pts)
        return if (p.isEmpty()) "세 국가 스폰 지점이 규칙을 지킵니다." else "규칙 위반: ${p.joinToString()}"
    }

    private fun status(c: CommandContext<CommandSourceStack>): Int {
        val now = System.currentTimeMillis()
        val reg = Nations.registry
        val lines = reg.nations.values.map { n ->
            val hp = reg.coreHp(n, now).toInt()
            val online = n.members.count { c.source.server.playerList.getPlayer(it) != null }
            "${n.id} ${n.name}${if (n.fallen) " (멸망)" else ""}: 국민 ${n.members.size}명(접속 $online), 영토 ${reg.territory(n.id).size}청크, 코어 ${if (n.core == null) "없음" else "$hp"}"
        }
        val rel = reg.nations.keys.toList().let { ids -> ids.flatMapIndexed { i, a -> ids.drop(i + 1).map { b -> "$a-$b ${relationName(reg.relation(a, b, now))}" } } }
        c.source.sendSuccess({ Component.literal((lines + rel.joinToString(", ")).joinToString("\n")) }, false)
        return 1
    }

    private fun claimArea(c: CommandContext<CommandSourceStack>, add: Boolean, r: Int): Int {
        val n = nationArg(c) ?: return fail(c, "그런 국가가 없습니다.")
        val pos = c.source.position
        val d = dim(c.source.level)
        val cx = pos.x.toInt() shr 4; val cz = pos.z.toInt() shr 4
        val chunks = (-r..r).flatMap { dx -> (-r..r).map { dz -> ChunkKey(d, cx + dx, cz + dz) } }
        return if (add) {
            val got = Nations.claimAndNotify(n.id, chunks)
            ok(c, "${n.name} 영토로 ${got.size}청크를 넣었습니다(이미 주인이 있는 청크는 건너뜀)."); 1
        } else {
            val owned = chunks.filter { Nations.registry.ownerOf(it)?.id == n.id }
            Nations.unclaimAndNotify(owned); ok(c, "${n.name} 영토에서 ${owned.size}청크를 풀었습니다."); 1
        }
    }

    /** 수도(코어가 있으면 코어 청크, 없으면 첫 영토)와 이어진 본토, 나머지 덩어리는 전초기지. */
    fun shape(n: NationRegistry.Nation, dimension: String): Triple<Chunk?, Set<Chunk>, List<Set<Chunk>>> {
        val own = Nations.registry.territory(n.id).filter { it.dimension == dimension }.map { Chunk(it.x, it.z) }.toSet()
        val capital = n.core?.takeIf { it.dimension == dimension }?.let { Chunk(it.x shr 4, it.z shr 4) } ?: own.firstOrNull()
        if (capital == null) return Triple(null, emptySet(), emptyList())
        val main = TerritoryRules.connectedFrom(capital, own)
        val rest = (own - main).toMutableSet(); val outposts = mutableListOf<Set<Chunk>>()
        while (rest.isNotEmpty()) { val part = TerritoryRules.connectedFrom(rest.first(), rest); outposts += part; rest -= part }
        return Triple(capital, main, outposts)
    }

    /** 보유 거점 수(거점전 모듈이 채운다). */
    var landmarksOwned: (String) -> Int = { 0 }
    fun cap(n: NationRegistry.Nation): Int = rules.cap(n.members.size, n.members.sumOf { Nations.zombieScore(it) }, landmarksOwned(n.id))

    /** 점령·해제(명령과 영토 지도 창이 같이 쓴다). 결과 문구와 성공 여부. */
    fun claimAt(me: net.minecraft.server.level.ServerPlayer, d: String, target: Chunk, add: Boolean): Pair<Boolean, String> {
        val n = Nations.registry.nationOf(me.uuid)?.takeIf { !it.fallen } ?: return false to "국가에 속해 있지 않습니다."
        val (capital, main, outposts) = shape(n, d)
        if (add) {
            if (capital == null) return false to "아직 수도가 없습니다. 운영자가 국가 코어를 놓으면 점령할 수 있습니다."
            val foreign = Nations.registry.allClaims().filter { it.key.dimension == d && it.value != n.id }.keys.map { Chunk(it.x, it.z) }.toSet()
            val asOutpost = main.none { m -> target.neighbors4().contains(m) } && outposts.none { o -> target.neighbors4().any { it in o } }
            rules.checkClaim(target, main, outposts, foreign, forbidden(d), cap(n), asNewOutpost = asOutpost)?.let { return false to it.message + "." }
            Nations.claimAndNotify(n.id, listOf(ChunkKey(d, target.x, target.z)))
            return true to "${n.name} 영토로 점령했습니다. (${Nations.registry.territory(n.id).size}/${cap(n)})"
        }
        if (me.uuid != dev.menonng.snw.season.Season.effectiveLeader(n) && me.uuid !in n.officers) return false to "영토 해제는 원수와 간부만 할 수 있습니다."
        if (capital == null) return false to "우리 땅이 아닙니다."
        rules.checkUnclaim(target, capital, main, outposts)?.let { return false to it.message + "." }
        Nations.unclaimAndNotify(listOf(ChunkKey(d, target.x, target.z)))
        return true to "점령을 풀었습니다."
    }

    /** 점령할 수 없는 청크: 생존자 캠프, 거점 구역(거점전 모듈이 채운다). */
    var landmarkChunks: (String) -> Set<Chunk> = { emptySet() }
    fun forbidden(d: String): Set<Chunk> {
        val camp = if (d == "minecraft:overworld") (-CAMP_CHUNK_RADIUS..CAMP_CHUNK_RADIUS).flatMap { x -> (-CAMP_CHUNK_RADIUS..CAMP_CHUNK_RADIUS).map { z -> Chunk(x, z) } }.toSet() else emptySet()
        return camp + landmarkChunks(d)
    }

    private fun playerClaim(c: CommandContext<CommandSourceStack>, add: Boolean): Int {
        val me = c.source.playerOrException
        val (okay, msg) = claimAt(me, dim(me.level()), Chunk(me.blockPosition().x shr 4, me.blockPosition().z shr 4), add)
        return if (okay) { ok(c, msg); 1 } else fail(c, msg)
    }

    /** 관계 바꾸기(원수). 명령과 관계 탭이 같이 쓴다. */
    fun changeRelation(me: net.minecraft.server.level.ServerPlayer, other: NationRegistry.Nation, want: Relation): Pair<Boolean, String> {
        val n = Nations.registry.nationOf(me.uuid)?.takeIf { !it.fallen } ?: return false to "국가에 속해 있지 않습니다."
        if (dev.menonng.snw.season.Season.effectiveLeader(n) != me.uuid) return false to "관계는 원수만 바꿀 수 있습니다."
        val now = System.currentTimeMillis()
        val before = Nations.registry.relation(n.id, other.id, now)
        val res = Nations.registry.request(n.id, other.id, want, now)
        val after = Nations.registry.relation(n.id, other.id, now)
        Nations.notifyRelation(n.id, other.id, before, after)
        val s = me.level().server
        when (res) {
            NationRegistry.RelationResult.SCHEDULED -> broadcast(s, "${n.name}이(가) ${other.name}에 적대를 선언했습니다. 12시간 뒤 발효되며, 그때부터 국가 코어를 공격할 수 있습니다.")
            NationRegistry.RelationResult.PROPOSED -> dev.menonng.snw.season.Season.effectiveLeader(other)?.let { s.playerList.getPlayer(it) }?.sendSystemMessage(Component.literal("${n.name}이(가) ${relationName(want)}을(를) 제안했습니다. 수락하려면 /n 의 관계 탭이나 /n relation ${n.id} ${relationName(want)} 를 쓰십시오."))
            NationRegistry.RelationResult.OK -> if (before != after) broadcast(s, "${n.name}·${other.name} 관계가 ${relationName(after)}(으)로 바뀌었습니다.")
            else -> {}
        }
        return (res in setOf(NationRegistry.RelationResult.OK, NationRegistry.RelationResult.PROPOSED, NationRegistry.RelationResult.SCHEDULED)) to res.message
    }

    private fun playerRelation(c: CommandContext<CommandSourceStack>): Int {
        val me = c.source.playerOrException
        val other = nationArg(c)?.takeIf { !it.fallen } ?: return fail(c, "그런 국가가 없습니다.")
        val want = parseRelation(StringArgumentType.getString(c, "relation")) ?: return fail(c, "관계는 동맹·중립·적대 중 하나입니다.")
        val (okay, msg) = changeRelation(me, other, want)
        return if (okay) { ok(c, msg); 1 } else fail(c, msg)
    }

    fun broadcast(s: net.minecraft.server.MinecraftServer, msg: String, rgb: Int = 0xFF7E00) {
        s.playerList.broadcastSystemMessage(Component.literal(msg).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb))), false)
    }

    private fun info(c: CommandContext<CommandSourceStack>): Int {
        val me = c.source.playerOrException
        val n = Nations.registry.nationOf(me.uuid)?.takeIf { !it.fallen } ?: return fail(c, "국가에 속해 있지 않습니다.")
        val now = System.currentTimeMillis()
        val s = c.source.server
        val leader = dev.menonng.snw.season.Season.effectiveLeader(n)?.let { Nations.nameOf(s, it) } ?: "없음"
        val rel = Nations.registry.nations.values.filter { it.id != n.id && !it.fallen }.joinToString(", ") { "${it.name} ${relationName(Nations.registry.relation(n.id, it.id, now))}" }
        val core = n.core?.let { "코어 ${Nations.registry.coreHp(n, now).toInt()} / 100,000" } ?: "코어 없음"
        me.sendSystemMessage(Component.literal("[${n.name}] 원수 $leader · 국민 ${n.members.size}명 · 영토 ${Nations.registry.territory(n.id).size}/${cap(n)}청크 · $core\n관계: $rel")
            .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(n.colorRgb))))
        return 1
    }
}
