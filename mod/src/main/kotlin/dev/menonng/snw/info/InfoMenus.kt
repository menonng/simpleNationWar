package dev.menonng.snw.info

import com.mojang.authlib.GameProfile
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.ServerStatsCounter
import net.minecraft.stats.Stats
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.TeamColor
import java.nio.file.Files
import java.util.UUID

/**
 * `/info` 상자 창(하이픽셀식). docs/design/nation.md '화면' 3절.
 * - `/info` → 플레이어 목록(머리 36칸, 국가 필터, 정렬, 쪽 넘김)
 * - 머리 클릭 또는 `/info <이름>` → 그 사람 정보 창
 * 창 안의 아이템은 꺼낼 수 없다(모든 클릭을 서버가 받아 처리하고 화면을 다시 보낸다).
 */
object InfoMenus {
    private const val ROWS = 6
    private const val PAGE_SIZE = 36
    private val PINK = TextColor.fromRgb(0xFFB4CC)
    private val SKY = TextColor.fromRgb(0x39C5BB)
    private val ZOMBIE_TYPES: List<EntityType<*>> = listOf(
        EntityTypes.ZOMBIE, EntityTypes.HUSK, EntityTypes.DROWNED, EntityTypes.ZOMBIE_VILLAGER,
        EntityTypes.ZOMBIFIED_PIGLIN, EntityTypes.ZOGLIN, EntityTypes.ZOMBIE_HORSE,
    )

    enum class Sort(val label: String) { ONLINE("접속 중 먼저"), NAME("이름순"), ZOMBIE("좀비 처치순"), KDA("KDA순") }

    /** 목록 창 상태. [nation]: null = 전체, "" = 무소속, 그 밖 = 팀 이름. */
    data class ListState(val nation: String? = null, val sort: Sort = Sort.ONLINE, val page: Int = 0)

    data class Entry(val id: UUID, val name: String, val online: ServerPlayer?)

    /** 정보 창에 보여 줄 숫자. 바닐라 통계에서 읽는다(zomApo·KDA 기록이 붙으면 [extraLines] 로 줄이 늘어난다). */
    data class InfoStats(val kills: Int, val deaths: Int, val zombieKills: Int, val mobKills: Int, val playTicks: Int) {
        val kda: Double get() = kills.toDouble() / maxOf(1, deaths)
    }

    /** 다른 모드가 정보 창 칸에 줄을 더한다(`snw:stat_provider`). 키 = 칸 이름("좀비", "생존" 등). */
    val extraLines: MutableList<(UUID, String) -> List<Component>> = mutableListOf()

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(command("info"))
            dispatcher.register(command("정보"))
        }
    }

    private fun command(name: String): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(name)
            .executes { ctx -> openList(ctx.source.playerOrException, ListState()); 1 }
            .then(
                Commands.argument("player", StringArgumentType.word())
                    .suggests { ctx, builder -> SharedSuggestionProvider.suggest(entries(ctx.source.server).flatMap { listOfNotNull(it.name, dev.menonng.snw.nickname.Nicknames.get(it.id)) }, builder) }
                    .executes { ctx -> openByName(ctx) },
            )

    private fun openByName(ctx: CommandContext<CommandSourceStack>): Int {
        val viewer = ctx.source.playerOrException
        val name = StringArgumentType.getString(ctx, "player")
        val entry = entries(ctx.source.server).firstOrNull { it.name.equals(name, ignoreCase = true) || dev.menonng.snw.nickname.Nicknames.get(it.id)?.equals(name, ignoreCase = true) == true }
        if (entry == null) {
            ctx.source.sendFailure(Component.literal("그런 플레이어가 없습니다: $name"))
            return 0
        }
        openProfile(viewer, entry, null)
        return 1
    }

    /** 닉네임이 있으면 닉네임(아래 줄에 마크 이름이 회색으로 함께 나온다). */
    private fun shown(e: Entry): String = dev.menonng.snw.nickname.Nicknames.get(e.id) ?: e.name

    /** 이번 월드에 접속한 적 있는 모든 플레이어(통계 파일 기준) + 지금 접속 중인 사람. */
    fun entries(server: MinecraftServer): List<Entry> {
        val out = LinkedHashMap<UUID, Entry>()
        for (p in server.playerList.players) out[p.uuid] = Entry(p.uuid, p.gameProfile.name(), p)
        val dir = server.getWorldPath(LevelResource.PLAYER_STATS_DIR)
        if (Files.isDirectory(dir)) {
            Files.list(dir).use { files ->
                for (f in files) {
                    val id = runCatching { UUID.fromString(f.fileName.toString().removeSuffix(".json")) }.getOrNull() ?: continue
                    if (id in out) continue
                    val name = server.services().nameToIdCache().get(id).map { it.name() }.orElse(null) ?: continue
                    out[id] = Entry(id, name, null)
                }
            }
        }
        return out.values.toList()
    }

    fun stats(server: MinecraftServer, e: Entry): InfoStats {
        val counter: ServerStatsCounter = e.online?.stats ?: run {
            val file = server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve("${e.id}.json")
            ServerStatsCounter(server, file)
        }
        fun custom(id: net.minecraft.resources.Identifier) = counter.getValue(Stats.CUSTOM.get(id))
        val zombies = ZOMBIE_TYPES.sumOf { counter.getValue(Stats.ENTITY_KILLED.get(it)) }
        return InfoStats(
            kills = custom(Stats.PLAYER_KILLS),
            deaths = custom(Stats.DEATHS),
            zombieKills = zombies,
            mobKills = custom(Stats.MOB_KILLS),
            playTicks = custom(Stats.PLAY_TIME),
        )
    }

    private fun team(server: MinecraftServer, name: String): PlayerTeam? = server.scoreboard.getPlayersTeam(name)

    private fun nationColor(team: PlayerTeam?): TextColor =
        team?.color?.orElse(null)?.textColor() ?: TextColor.fromRgb(0xFFFFFF)

    private fun text(s: String, color: TextColor? = null): MutableComponent =
        Component.literal(s).withStyle(Style.EMPTY.withItalic(false).let { if (color != null) it.withColor(color) else it })

    private fun gray(s: String) = text(s, TextColor.fromLegacyFormat(ChatFormatting.GRAY))

    private fun item(item: Item, name: Component, lore: List<Component> = emptyList(), glint: Boolean = false, hide: Set<DataComponentType<*>> = emptySet()): ItemStack =
        ItemStack(item).apply {
            set(DataComponents.CUSTOM_NAME, name)
            if (lore.isNotEmpty()) set(DataComponents.LORE, ItemLore(lore))
            if (glint) set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
            if (hide.isNotEmpty()) set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay(false, LinkedHashSet(hide)))
        }

    private fun filler(): ItemStack = ItemStack(Items.STAINED_GLASS_PANE.black()).apply {
        set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay(true, LinkedHashSet()))
    }

    private fun head(e: Entry, name: Component, lore: List<Component>): ItemStack = ItemStack(Items.PLAYER_HEAD).apply {
        val profile = e.online?.gameProfile ?: GameProfile(e.id, e.name)
        set(DataComponents.PROFILE, if (e.online != null) ResolvableProfile.createResolved(profile) else ResolvableProfile.createUnresolved(e.id))
        set(DataComponents.CUSTOM_NAME, name)
        set(DataComponents.LORE, ItemLore(lore))
        // 바닐라 머리 설명("동적" 등)을 숨긴다
        set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay(false, linkedSetOf<DataComponentType<*>>(DataComponents.PROFILE)))
    }

    private fun lastSeen(server: MinecraftServer, e: Entry): String {
        if (e.online != null) return "접속 중"
        val file = server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve("${e.id}.json")
        val modified = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull() ?: return "접속 안 함"
        val hours = (System.currentTimeMillis() - modified) / 3_600_000L
        return if (hours < 1) "방금 전" else if (hours < 48) "${hours}시간 전" else "${hours / 24}일 전"
    }

    private fun hours(ticks: Int): String {
        val minutes = ticks / 20 / 60
        return if (minutes < 60) "${minutes}분" else "${minutes / 60}시간 ${minutes % 60}분"
    }

    // ---------------------------------------------------------------- 목록

    fun openList(viewer: ServerPlayer, state: ListState) {
        val server = viewer.level().server
        val all = entries(server)
        val statsCache = HashMap<UUID, InfoStats>()
        fun st(e: Entry) = statsCache.getOrPut(e.id) { stats(server, e) }
        val teams = server.scoreboard.playerTeams.sortedBy { it.name }.take(4)
        val filtered = all.filter { e ->
            when (state.nation) {
                null -> true
                "" -> team(server, e.name) == null
                else -> team(server, e.name)?.name == state.nation
            }
        }
        val sorted = when (state.sort) {
            Sort.ONLINE -> filtered.sortedWith(compareBy<Entry>({ it.online == null }, { team(server, it.name)?.name ?: "~" }, { it.name.lowercase() }))
            Sort.NAME -> filtered.sortedBy { it.name.lowercase() }
            Sort.ZOMBIE -> filtered.sortedByDescending { st(it).zombieKills }
            Sort.KDA -> filtered.sortedByDescending { st(it).kda }
        }
        val pages = maxOf(1, (sorted.size + PAGE_SIZE - 1) / PAGE_SIZE)
        val page = state.page.coerceIn(0, pages - 1)
        val container = SimpleContainer(ROWS * 9)
        for (i in 0 until 9) { container.setItem(i, filler()); container.setItem(45 + i, filler()) }
        val actions = HashMap<Int, (ServerPlayer) -> Unit>()

        // 1줄: 내 정보, 국가 필터, 정렬
        val me = all.firstOrNull { it.id == viewer.uuid } ?: Entry(viewer.uuid, viewer.gameProfile.name(), viewer)
        container.setItem(0, head(me, text("내 정보", SKY), listOf(gray("클릭해서 내 정보 창 열기"))))
        actions[0] = { p -> openProfile(p, me, state) }
        val filters = mutableListOf<Triple<String?, Item, Component>>(Triple(null, Items.BANNER.white(), text("전체")))
        for (t in teams) filters += Triple(t.name, bannerFor(t), text(t.displayName.string, nationColor(t)))
        filters += Triple("", Items.BANNER.lightGray(), gray("무소속"))
        filters.take(6).forEachIndexed { i, (key, banner, label) ->
            val slot = 2 + i
            val selected = state.nation == key
            container.setItem(slot, item(banner, label, listOf(gray(if (selected) "보는 중" else "클릭해서 이 국가만 보기")), glint = selected, hide = setOf(DataComponents.BANNER_PATTERNS)))
            actions[slot] = { p -> openList(p, state.copy(nation = key, page = 0)) }
        }
        val sortLore = Sort.entries.map { s -> if (s == state.sort) text("▶ ${s.label}", SKY) else gray("  ${s.label}") }
        container.setItem(8, item(Items.HOPPER, text("정렬: ${state.sort.label}"), sortLore))
        actions[8] = { p -> openList(p, state.copy(sort = Sort.entries[(state.sort.ordinal + 1) % Sort.entries.size], page = 0)) }

        // 2~5줄: 머리
        sorted.drop(page * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { i, e ->
            val slot = 9 + i
            val t = team(server, e.name)
            val s = st(e)
            val lore = listOf(
                gray(e.name),
                gray("${t?.displayName?.string ?: "무소속"} · ${lastSeen(server, e)}"),
                gray("KDA %.2f · 좀비 처치 %,d".format(s.kda, s.zombieKills)),
                text("▶ 클릭해서 정보 보기", SKY),
            )
            container.setItem(slot, head(e, text(shown(e), nationColor(t)), lore))
            actions[slot] = { p -> openProfile(p, e, state.copy(page = page)) }
        }

        // 6줄: 쪽 넘김, 닫기
        if (page > 0) {
            container.setItem(45, item(Items.ARROW, text("◀ 이전 쪽")))
            actions[45] = { p -> openList(p, state.copy(page = page - 1)) }
        }
        container.setItem(49, item(Items.PAPER, text("쪽 ${page + 1}/$pages"), listOf(gray("플레이어 ${sorted.size}명"))))
        if (page < pages - 1) {
            container.setItem(53, item(Items.ARROW, text("다음 쪽 ▶")))
            actions[53] = { p -> openList(p, state.copy(page = page + 1)) }
        }
        container.setItem(52, item(Items.BARRIER, text("닫기")))
        actions[52] = { p -> p.closeContainer() }

        open(viewer, Component.literal("플레이어 정보"), container, actions)
    }

    private fun bannerFor(team: PlayerTeam): Item = when (team.color.orElse(null)) {
        TeamColor.RED, TeamColor.DARK_RED -> Items.BANNER.red()
        TeamColor.BLUE, TeamColor.DARK_BLUE -> Items.BANNER.blue()
        TeamColor.YELLOW, TeamColor.GOLD -> Items.BANNER.yellow()
        TeamColor.GREEN, TeamColor.DARK_GREEN -> Items.BANNER.green()
        TeamColor.AQUA, TeamColor.DARK_AQUA -> Items.BANNER.cyan()
        TeamColor.LIGHT_PURPLE, TeamColor.DARK_PURPLE -> Items.BANNER.purple()
        else -> Items.BANNER.white()
    }

    // ---------------------------------------------------------------- 정보 창

    fun openProfile(viewer: ServerPlayer, e: Entry, back: ListState?) {
        val server = viewer.level().server
        val t = team(server, e.name)
        val color = nationColor(t)
        val s = stats(server, e)
        val container = SimpleContainer(ROWS * 9)
        for (i in 0 until ROWS * 9) if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) container.setItem(i, filler())
        val actions = HashMap<Int, (ServerPlayer) -> Unit>()
        fun extra(section: String) = extraLines.flatMap { it(e.id, section) }

        container.setItem(4, head(e, text(shown(e), color), listOf(
            gray(e.name),
            text(t?.displayName?.string ?: "무소속", color),
            gray(lastSeen(server, e)),
        )))
        container.setItem(19, item(if (t != null) bannerFor(t) else Items.BANNER.lightGray(), text("국가", color), listOf(
            text(t?.displayName?.string ?: "무소속", color),
        ) + extra("국가"), hide = setOf(DataComponents.BANNER_PATTERNS)))
        container.setItem(21, item(Items.IRON_SWORD, text("전투"), listOf(
            dev.menonng.snw.protect.Protection.stats(e.id).let { k -> gray("K ${k.kills}   D ${k.deaths}   A ${k.assists}") },
            dev.menonng.snw.protect.Protection.stats(e.id).let { k -> gray("KDA %.2f".format(k.kda)) },
            dev.menonng.snw.protect.Protection.stats(e.id).let { k -> gray("PvP 기준 KDA %.2f (PvP 사망 ${k.deathsPvp})".format(k.kdaPvp)) },
            dev.menonng.snw.protect.Protection.stats(e.id).let { k -> gray("아군 사살 ${k.teamKills}") },
        ) + extra("전투"), hide = setOf(DataComponents.ATTRIBUTE_MODIFIERS)))
        container.setItem(23, item(Items.ROTTEN_FLESH, text("좀비"), listOf(
            gray("좀비 처치 %,d".format(s.zombieKills)),
            gray("몬스터 처치 %,d".format(s.mobKills)),
        ) + extra("좀비")))
        container.setItem(25, item(Items.TOTEM_OF_UNDYING, text("생존"), listOf(
            gray("죽음 ${s.deaths}"),
        ) + extra("생존")))
        container.setItem(29, item(Items.CLOCK, text("플레이 시간"), listOf(
            gray("합계 ${hours(s.playTicks)}"),
        ) + extra("시간")))
        val titles = extra("칭호")
        container.setItem(33, item(Items.NAME_TAG, text("칭호", PINK), titles.ifEmpty { listOf(gray("아직 칭호가 없습니다")) }))

        container.setItem(45, item(Items.ARROW, text("◀ 목록")))
        actions[45] = { p -> openList(p, back ?: ListState()) }
        container.setItem(49, item(Items.BARRIER, text("닫기")))
        actions[49] = { p -> p.closeContainer() }

        open(viewer, Component.literal("${shown(e)}의 정보"), container, actions)
    }

    private fun open(viewer: ServerPlayer, title: Component, container: SimpleContainer, actions: Map<Int, (ServerPlayer) -> Unit>) {
        viewer.openMenu(SimpleMenuProvider({ id, inv, _ -> ReadOnlyMenu(id, inv, container, actions) }, title))
    }

    /** 꺼낼 수 없는 상자 창. 칸을 누르면 [actions] 의 동작만 한다. */
    class ReadOnlyMenu(id: Int, inv: Inventory, container: SimpleContainer, private val actions: Map<Int, (ServerPlayer) -> Unit>) :
        ChestMenu(MenuType.GENERIC_9x6, id, inv, container, ROWS) {

        override fun clicked(slotId: Int, button: Int, input: ContainerInput, player: Player) {
            // 클라이언트가 미리 옮겨 그린 것을 되돌린다(서버에서는 아무것도 옮기지 않는다).
            sendAllDataToRemote()
            if (player !is ServerPlayer) return
            if (input != ContainerInput.PICKUP && input != ContainerInput.QUICK_MOVE) return
            actions[slotId]?.invoke(player)
        }

        override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY
        override fun stillValid(player: Player): Boolean = true
        override fun canTakeItemForPickAll(stack: ItemStack, slot: Slot): Boolean = false
        override fun canDragTo(slot: Slot): Boolean = false
    }
}
