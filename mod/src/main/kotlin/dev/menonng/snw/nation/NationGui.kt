package dev.menonng.snw.nation

import com.mojang.authlib.GameProfile
import dev.menonng.snw.api.ChunkKey
import dev.menonng.snw.api.Relation
import dev.menonng.snw.info.InfoMenus
import dev.menonng.snw.logic.state.NationRegistry
import dev.menonng.snw.logic.territory.Chunk
import dev.menonng.snw.nickname.Nicknames
import dev.menonng.snw.protect.Protection
import dev.menonng.snw.season.Season
import dev.menonng.snw.ui.Gui
import net.minecraft.core.component.DataComponents
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import java.util.UUID

/**
 * `/n` 국가 창(nation.md '화면' 2절): 1줄 국가 요약, 2줄 탭, 3~6줄 내용.
 * 국민·영토 지도·국고·관계는 여기서, 거점·연구 탭은 각 모듈이 [landmarkTab]·[researchTab] 으로 채운다.
 */
object NationGui {
    enum class Tab(val label: String, val icon: Item) {
        MEMBERS("국민", Items.PLAYER_HEAD), TERRITORY("영토", Items.FILLED_MAP), TREASURY("국고", Items.CHEST),
        RELATIONS("관계", Items.BANNER.white()), LANDMARKS("거점", Items.LODESTONE), RESEARCH("연구", Items.BREWING_STAND)
    }

    var landmarkTab: (Gui, ServerPlayer, NationRegistry.Nation) -> Unit = { g, _, _ -> g.set(31, Gui.item(Items.LODESTONE, Gui.text("거점전 준비 중"))) }
    var researchTab: (Gui, ServerPlayer, NationRegistry.Nation) -> Unit = { g, _, _ -> g.set(31, Gui.item(Items.BREWING_STAND, Gui.text("연구 준비 중"))) }

    private val mapOffset = HashMap<UUID, Pair<Int, Int>>()

    fun open(p: ServerPlayer, tab: Tab = Tab.MEMBERS) {
        val n = Nations.registry.nationOf(p.uuid)?.takeIf { !it.fallen }
        if (n == null) { if (Season.state.started) Season.openChooser(p) else p.sendSystemMessage(Gui.text("아직 국가에 속해 있지 않습니다."), true); return }
        val gui = Gui(Gui.text("${n.name} · ${tab.label}", Gui.rgb(n.colorRgb)))
        header(gui, p, n)
        Tab.entries.forEachIndexed { i, t ->
            gui.set(10 + i, Gui.item(t.icon, Gui.text(t.label, if (t == tab) Gui.rgb(n.colorRgb) else null), glint = t == tab,
                hide = setOf(DataComponents.BANNER_PATTERNS, DataComponents.PROFILE))) { open(it, t) }
        }
        when (tab) {
            Tab.MEMBERS -> members(gui, p, n)
            Tab.TERRITORY -> territory(gui, p, n)
            Tab.TREASURY -> treasury(gui, p, n)
            Tab.RELATIONS -> relations(gui, p, n)
            Tab.LANDMARKS -> landmarkTab(gui, p, n)
            Tab.RESEARCH -> researchTab(gui, p, n)
        }
        for (i in 0 until 18) if (gui.container.getItem(i).isEmpty) gui.container.setItem(i, Gui.filler())
        gui.open(p)
    }

    private fun header(gui: Gui, p: ServerPlayer, n: NationRegistry.Nation) {
        val s = p.level().server
        val now = System.currentTimeMillis()
        val leader = Season.effectiveLeader(n, now)?.let { Nicknames.get(it) ?: Nations.nameOf(s, it) } ?: "없음"
        val lv = Nations.infectionLevel?.invoke(p.level().dimension().identifier().toString(), p.blockPosition().x shr 4, p.blockPosition().z shr 4)
        val core = n.core?.let { "코어 ${"%,d".format(Nations.registry.coreHp(n, now).toInt())} / 100,000" } ?: "코어 없음"
        gui.set(0, Gui.item(Gui.banner(n.colorRgb), Gui.text(n.name, Gui.rgb(n.colorRgb)), listOfNotNull(
            Gui.gray("원수 $leader · 국민 ${n.members.size}명"),
            Gui.gray("영토 ${Nations.registry.territory(n.id).size} / ${NationCommands.cap(n)}"),
            Gui.gray(core),
            Gui.gray(dev.menonng.snw.project.Villagers.summary(n.id)),
            lv?.let { Gui.gray("지금 이곳 감염 Lv$it") },
            Gui.gray("오늘 공성 시간 ${dev.menonng.snw.core.SiegeSchedule.todayLabel(now)}"),
        ), hide = setOf(DataComponents.BANNER_PATTERNS)))
        gui.set(8, Gui.item(Items.BARRIER, Gui.text("닫기"))) { it.closeContainer() }
    }

    private fun roleName(n: NationRegistry.Nation, id: UUID) = when {
        id == Season.effectiveLeader(n) -> "원수"
        id in n.officers -> "간부"
        else -> "국민"
    }

    private fun members(gui: Gui, p: ServerPlayer, n: NationRegistry.Nation) {
        val s = p.level().server
        val now = System.currentTimeMillis()
        val order = n.members.sortedWith(compareBy<UUID>({ s.playerList.getPlayer(it) == null }, { when (roleName(n, it)) { "원수" -> 0; "간부" -> 1; else -> 2 } },
            { -(Season.state.lastSeen[it.toString()] ?: 0L) }))
        order.take(36).forEachIndexed { i, id ->
            val name = Nations.nameOf(s, id) ?: "?"
            val online = s.playerList.getPlayer(id)
            val seen = if (online != null) "접속 중" else Season.state.lastSeen[id.toString()]?.let { ago(now - it) } ?: "기록 없음"
            val k = Protection.stats(id)
            val lore = mutableListOf<net.minecraft.network.chat.Component>(Gui.gray(name), Gui.gray("${roleName(n, id)} · $seen"), Gui.gray("K ${k.kills}   KDA %.2f".format(k.kda)))
            lore += InfoMenus.extraLines.flatMap { it(id, "국민") }
            lore += Gui.gray("이번 주 플레이 ${Protection.afk.activeMillis(id, now, 168) / 3_600_000}시간")
            val head = ItemStack(Items.PLAYER_HEAD).apply {
                set(DataComponents.PROFILE, ResolvableProfile.createResolved(online?.gameProfile ?: GameProfile(id, name)))
                set(DataComponents.CUSTOM_NAME, Gui.text(Nicknames.get(id) ?: name, Gui.rgb(n.colorRgb)))
                set(DataComponents.LORE, net.minecraft.world.item.component.ItemLore(lore))
                if (online != null) set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
            }
            gui.set(18 + i, head) { v -> InfoMenus.openProfile(v, InfoMenus.Entry(id, name, online), null) }
        }
    }

    private fun ago(ms: Long): String = when {
        ms < 3_600_000 -> "${ms / 60_000}분 전"
        ms < 86_400_000 -> "${ms / 3_600_000}시간 전"
        else -> "${ms / 86_400_000}일 전"
    }

    private fun pane(rgb: Int): Item = when (rgb) {
        0xFF0045 -> Items.STAINED_GLASS_PANE.red()
        0x3355BB -> Items.STAINED_GLASS_PANE.blue()
        0xFFCC11 -> Items.STAINED_GLASS_PANE.yellow()
        else -> Items.STAINED_GLASS_PANE.white()
    }

    /** 영토 지도: 9×4 청크 격자(가운데가 지금 청크). 좌클릭 점령, 우클릭(간부 이상) 해제. 아래 줄 화살표로 이동. */
    private fun territory(gui: Gui, p: ServerPlayer, n: NationRegistry.Nation) {
        val d = p.level().dimension().identifier().toString()
        val (ox, oz) = mapOffset[p.uuid] ?: (0 to 0)
        val cx = (p.blockPosition().x shr 4) + ox; val cz = (p.blockPosition().z shr 4) + oz
        val forbidden = NationCommands.forbidden(d)
        val ally = Nations.registry.allyOf(n.id)
        for (row in 0 until 4) for (col in 0 until 9) {
            val x = cx + col - 4; val z = cz + row - 2
            val owner = Nations.registry.ownerOf(ChunkKey(d, x, z))
            val here = ox == 0 && oz == 0 && col == 4 && row == 2
            val lv = Nations.infectionLevel?.invoke(d, x, z)
            val (item, label) = when {
                Chunk(x, z) in forbidden -> Items.OAK_SIGN to "점령 불가(캠프·거점)"
                owner?.id == n.id -> pane(n.colorRgb) to "자국 영토"
                owner != null && owner.id == ally -> Items.STAINED_GLASS_PANE.lightBlue() to "${owner.name} (동맹)"
                owner != null -> pane(owner.colorRgb) to owner.name
                lv != null && lv >= 4 -> Items.STAINED_GLASS_PANE.red() to "빈 땅"
                lv != null && lv >= 2 -> Items.STAINED_GLASS_PANE.purple() to "빈 땅"
                lv != null -> Items.STAINED_GLASS_PANE.green() to "빈 땅"
                else -> Items.STAINED_GLASS_PANE.white() to "빈 땅"
            }
            val lore = listOfNotNull(Gui.gray("청크 $x, $z"), lv?.let { Gui.gray("감염 Lv$it") }, if (here) Gui.gray("지금 있는 곳") else null,
                Gui.gray("좌클릭: 점령 · 우클릭: 해제(간부 이상)"))
            gui.setClick(18 + row * 9 + col, Gui.item(item, Gui.text(label), lore, glint = here)) { pl, button ->
                val (okay, msg) = NationCommands.claimAt(pl, d, Chunk(x, z), add = button == 0)
                pl.sendSystemMessage(Gui.text(msg, Gui.rgb(if (okay) 0x55BB44 else 0xFF7E00)), true)
                open(pl, Tab.TERRITORY)
            }
        }
        fun move(dx: Int, dz: Int): (ServerPlayer) -> Unit = { pl -> val (a, b) = mapOffset[pl.uuid] ?: (0 to 0); mapOffset[pl.uuid] = (a + dx) to (b + dz); open(pl, Tab.TERRITORY) }
        // 3줄째 오른쪽 끝·왼쪽 끝 등은 이미 지도이므로 화살표는 헤더 줄 빈칸에 둔다
        gui.set(2, Gui.item(Items.ARROW, Gui.text("← 서쪽"))) { move(-4, 0)(it) }
        gui.set(3, Gui.item(Items.ARROW, Gui.text("↑ 북쪽"))) { move(0, -2)(it) }
        gui.set(4, Gui.item(Items.COMPASS, Gui.text("내 자리로"), listOf(Gui.gray("영토 ${Nations.registry.territory(n.id).size} / ${NationCommands.cap(n)}")))) { pl -> mapOffset.remove(pl.uuid); open(pl, Tab.TERRITORY) }
        gui.set(5, Gui.item(Items.ARROW, Gui.text("↓ 남쪽"))) { move(0, 2)(it) }
        gui.set(6, Gui.item(Items.ARROW, Gui.text("→ 동쪽"))) { move(4, 0)(it) }
    }

    private fun treasury(gui: Gui, p: ServerPlayer, n: NationRegistry.Nation) {
        val reason = Treasury.canOpen(p)
        gui.set(30, Gui.item(Items.CHEST, Gui.text("국고 열기"), listOf(
            Gui.gray("열린 칸 ${Treasury.unlocked(n.id)} / 54"),
            if (reason == null) Gui.gray("눌러서 엽니다") else Gui.text(reason, Gui.rgb(0xFF7E00)),
        ))) { Treasury.open(it) }
        if (p.uuid == Season.effectiveLeader(n) || p.uuid in n.officers)
            gui.set(32, Gui.item(Items.WRITABLE_BOOK, Gui.text("국고 기록"), listOf(Gui.gray("최근 넣기·꺼내기")))) { Treasury.openLog(it) }
        val found = Treasury.DISCOVERIES.map { (k, label) -> Gui.gray("$label  ${if (k in (Treasury.discoveries(n.id))) "✔" else "—"}") }
        gui.set(40, Gui.item(Items.BARRIER, Gui.text("칸 해금"), found))
    }

    private fun relations(gui: Gui, p: ServerPlayer, n: NationRegistry.Nation) {
        val now = System.currentTimeMillis()
        val leader = Season.effectiveLeader(n, now) == p.uuid
        Nations.registry.alive().filter { it.id != n.id }.forEachIndexed { i, o ->
            val r = Nations.registry.relation(n.id, o.id, now)
            val st = Nations.registry.relationState(n.id, o.id)
            val extra = mutableListOf<net.minecraft.network.chat.Component>()
            st.hostileAt?.let { extra += Gui.text("적대 발효까지 ${(it - now) / 60_000}분", Gui.rgb(0xFF7E00)) }
            if (st.rehostileAfter > now) extra += Gui.gray("다시 적대할 수 있을 때까지 ${(st.rehostileAfter - now) / 3_600_000}시간")
            st.allyProposedBy?.let { extra += Gui.gray(if (it == n.id) "동맹을 제안해 두었습니다" else "동맹 제안을 받았습니다") }
            st.peaceProposedBy?.let { extra += Gui.gray(if (it == n.id) "적대 해제를 제안해 두었습니다" else "적대 해제 제안을 받았습니다") }
            val col = 20 + i * 4
            gui.set(col, Gui.item(Gui.banner(o.colorRgb), Gui.text(o.name, Gui.rgb(o.colorRgb)), listOf(Gui.gray("지금: ${NationCommands.relationName(r)}")) + extra,
                hide = setOf(DataComponents.BANNER_PATTERNS)))
            if (!leader) return@forEachIndexed
            fun btn(slot: Int, item: Item, label: String, want: Relation) = gui.set(slot, Gui.item(item, Gui.text(label))) { pl ->
                val (okay, msg) = NationCommands.changeRelation(pl, o, want)
                pl.sendSystemMessage(Gui.text(msg, Gui.rgb(if (okay) 0x55BB44 else 0xFF7E00)), true); open(pl, Tab.RELATIONS)
            }
            btn(col + 18, Items.EMERALD, "동맹 제안·수락", Relation.ALLIED)
            btn(col + 27, Items.PAPER, "중립(동맹 파기·적대 해제)", Relation.NEUTRAL)
            btn(col + 36, Items.IRON_SWORD, "적대 선언(12시간 뒤)", Relation.HOSTILE)
        }
        if (!leader) gui.set(49, Gui.item(Items.BOOK, Gui.text("관계 변경은 원수만 할 수 있습니다")))
    }
}
