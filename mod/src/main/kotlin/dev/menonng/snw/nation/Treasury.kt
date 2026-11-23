package dev.menonng.snw.nation

import com.google.gson.GsonBuilder
import dev.menonng.snw.api.ChunkKey
import dev.menonng.snw.ui.Gui
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.util.UUID

/**
 * 국고(nation.md 9-1): 국가 공용 창고 54칸(가상). **자국 영토 안에서만** 열린다. 털리지 않는 유일한 보관함.
 * - 넣기·꺼내기 기록(최근 200건 저장, 원수·간부가 최근 50건을 본다).
 * - 칸 해금: 처음 27칸, 국가의 '발견'(거점 첫 점령, 백신 조각 첫 확보, 블러드문 첫 방어 성공)마다 9칸씩.
 */
object Treasury {
    const val BASE_SLOTS = 27
    const val PER_DISCOVERY = 9
    val DISCOVERIES = mapOf("landmark" to "거점 첫 점령", "vaccine" to "백신 조각 첫 확보", "bloodmoon" to "블러드문 첫 방어 성공")

    data class LogEntry(val t: Long, val who: String, val item: String, val delta: Int)
    data class Meta(val discoveries: MutableMap<String, MutableSet<String>> = linkedMapOf(), val log: MutableMap<String, MutableList<LogEntry>> = linkedMapOf())

    private val containers = HashMap<String, SimpleContainer>()
    private var meta = Meta()
    private val gson = GsonBuilder().create()

    private fun dir(s: MinecraftServer) = s.getWorldPath(LevelResource.ROOT).resolve("snw").resolve("treasury")

    fun unlocked(nation: String): Int = (BASE_SLOTS + PER_DISCOVERY * (meta.discoveries[nation]?.size ?: 0)).coerceAtMost(54)

    /** 국가의 '발견'(한 번만). 새로 열렸으면 true. */
    fun discover(s: MinecraftServer, nation: String, key: String): Boolean {
        val set = meta.discoveries.getOrPut(nation) { linkedSetOf() }
        if (!set.add(key)) return false
        Nations.registry.nation(nation)?.let { n ->
            for (m in n.members) s.playerList.getPlayer(m)?.sendSystemMessage(Gui.text("${DISCOVERIES[key] ?: key}! 국고 칸이 ${unlocked(nation)}칸으로 늘었습니다.", Gui.rgb(n.colorRgb)))
        }
        save(s); return true
    }

    fun discoveries(nation: String): Set<String> = meta.discoveries[nation].orEmpty()

    /** 국가 멸망(5-1-1): 국고를 받은 국가 국고로 합치고, 자리가 없거나 받을 국가가 없으면 [dropAt] 에 보존 드랍. */
    fun mergeOnFall(s: MinecraftServer, from: String, to: String?, dropAt: Pair<net.minecraft.server.level.ServerLevel, net.minecraft.world.phys.Vec3>?) {
        val src = containers.remove(from) ?: return
        val dst = to?.let { container(s, it) }
        for (i in 0 until src.containerSize) {
            var st = src.getItem(i)
            if (st.isEmpty || locked(st)) continue
            if (dst != null) st = dst.addItem(st)
            if (!st.isEmpty && dropAt != null) {
                val ie = net.minecraft.world.entity.item.ItemEntity(dropAt.first, dropAt.second.x, dropAt.second.y, dropAt.second.z, st)
                ie.setUnlimitedLifetime(); dropAt.first.addFreshEntity(ie)
            }
        }
        save(s)
    }

    fun container(s: MinecraftServer, nation: String): SimpleContainer = containers.getOrPut(nation) { SimpleContainer(54) }

    fun log(nation: String): List<LogEntry> = meta.log[nation].orEmpty()

    fun canOpen(p: ServerPlayer): String? {
        val n = Nations.registry.nationOf(p.uuid)?.takeIf { !it.fallen } ?: return "국가에 속해 있지 않습니다."
        val here = ChunkKey(p.level().dimension().identifier().toString(), p.blockPosition().x shr 4, p.blockPosition().z shr 4)
        if (Nations.registry.ownerOf(here)?.id != n.id) return "국고는 자국 영토 안에서만 열립니다."
        return null
    }

    fun open(p: ServerPlayer) {
        canOpen(p)?.let { p.sendSystemMessage(Gui.text(it, Gui.rgb(0xFF7E00)), true); return }
        val n = Nations.registry.nationOf(p.uuid)!!
        val s = p.level().server
        val c = container(s, n.id)
        val before = snapshot(c)
        p.openMenu(SimpleMenuProvider({ id, inv, _ -> Menu(id, inv, c, unlocked(n.id), n.id, before) }, Component.literal("${n.name} 국고")))
    }

    private fun snapshot(c: SimpleContainer): Map<String, Int> {
        val m = HashMap<String, Int>()
        for (i in 0 until c.containerSize) { val st = c.getItem(i); if (!st.isEmpty && !locked(st)) m.merge(BuiltInRegistries.ITEM.getKey(st.item).path, st.count, Int::plus) }
        return m
    }

    private const val LOCK_TAG = "snw_locked_slot"
    private fun locked(st: ItemStack) = st.`is`(Items.BARRIER) && st.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA)?.copyTag()?.contains(LOCK_TAG) == true
    private fun lockItem(): ItemStack = Gui.item(Items.BARRIER, Gui.text("잠긴 칸"), DISCOVERIES.values.map { Gui.gray("$it 마다 9칸") }).apply {
        set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(CompoundTag().apply { putBoolean(LOCK_TAG, true) }))
    }

    /** 잠긴 칸은 방벽 표시만 있고 넣고 뺄 수 없다. 닫을 때 바뀐 만큼 기록한다. */
    class Menu(id: Int, inv: Inventory, private val c: SimpleContainer, private val open: Int, private val nation: String, private val before: Map<String, Int>) :
        ChestMenu(MenuType.GENERIC_9x6, id, inv, c, 6) {
        init { for (i in open until 54) c.setItem(i, lockItem()) }

        override fun clicked(slotId: Int, button: Int, input: ContainerInput, player: Player) {
            if (slotId in open until 54) { sendAllDataToRemote(); return }
            super.clicked(slotId, button, input, player)
        }

        override fun quickMoveStack(player: Player, index: Int): ItemStack {
            if (index in open until 54) return ItemStack.EMPTY
            if (index >= 54) {
                // 플레이어 인벤토리 → 열린 칸에만
                val slot = slots[index]
                if (!slot.hasItem()) return ItemStack.EMPTY
                val st = slot.item
                val copy = st.copy()
                if (!moveItemStackTo(st, 0, open, false)) return ItemStack.EMPTY
                if (st.isEmpty) slot.setByPlayer(ItemStack.EMPTY) else slot.setChanged()
                return copy
            }
            return super.quickMoveStack(player, index)
        }

        override fun removed(player: Player) {
            super.removed(player)
            for (i in open until 54) if (locked(c.getItem(i))) c.setItem(i, ItemStack.EMPTY)
            val after = snapshot(c)
            val who = (player as? ServerPlayer)?.uuid?.toString() ?: return
            val log = meta.log.getOrPut(nation) { mutableListOf() }
            for (k in before.keys + after.keys) {
                val d = (after[k] ?: 0) - (before[k] ?: 0)
                if (d != 0) log += LogEntry(System.currentTimeMillis(), who, k, d)
            }
            while (log.size > 200) log.removeAt(0)
            (player as ServerPlayer).level().server.let { save(it) }
        }

        override fun stillValid(player: Player): Boolean = player is ServerPlayer && canOpen(player) == null
    }

    fun openLog(p: ServerPlayer) {
        val n = Nations.registry.nationOf(p.uuid) ?: return
        if (p.uuid != dev.menonng.snw.season.Season.effectiveLeader(n) && p.uuid !in n.officers) { p.sendSystemMessage(Gui.text("국고 기록은 원수와 간부만 볼 수 있습니다."), true); return }
        val s = p.level().server
        val gui = Gui(Gui.text("${n.name} 국고 기록"))
        val zone = java.time.ZoneId.of("Asia/Seoul")
        log(n.id).takeLast(45).reversed().forEachIndexed { i, e ->
            val who = runCatching { Nations.nameOf(s, UUID.fromString(e.who)) }.getOrNull() ?: "?"
            val nick = runCatching { dev.menonng.snw.nickname.Nicknames.get(UUID.fromString(e.who)) }.getOrNull() ?: who
            val item = BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.withDefaultNamespace(e.item))
            val time = java.time.Instant.ofEpochMilli(e.t).atZone(zone).let { "%d/%d %02d:%02d".format(it.monthValue, it.dayOfMonth, it.hour, it.minute) }
            gui.set(i, Gui.item(if (item == Items.AIR) Items.PAPER else item, Gui.text("${if (e.delta > 0) "넣음 +" else "꺼냄 "}${e.delta}", Gui.rgb(if (e.delta > 0) 0x55BB44 else 0xFF7E00)),
                listOf(Gui.gray(nick), Gui.gray(time))))
        }
        gui.set(49, Gui.item(Items.ARROW, Gui.text("돌아가기"))) { NationGui.open(it, NationGui.Tab.TREASURY) }
        gui.open(p)
    }

    // ---- 저장 ----
    fun save(s: MinecraftServer) {
        val d = dir(s); Files.createDirectories(d)
        val ops = s.registryAccess().createSerializationContext(NbtOps.INSTANCE)
        for ((nation, c) in containers) {
            val list = ListTag()
            for (i in 0 until c.containerSize) {
                val st = c.getItem(i)
                if (st.isEmpty || locked(st)) continue
                ItemStack.CODEC.encodeStart(ops, st).result().ifPresent { t -> list.add(CompoundTag().apply { putInt("slot", i); put("item", t) }) }
            }
            NbtIo.writeCompressed(CompoundTag().apply { put("items", list) }, d.resolve("$nation.dat"))
        }
        Files.writeString(d.resolve("meta.json"), gson.toJson(meta))
    }

    fun load(s: MinecraftServer) {
        val d = dir(s)
        containers.clear()
        if (!Files.isDirectory(d)) return
        val ops = s.registryAccess().createSerializationContext(NbtOps.INSTANCE)
        Files.list(d).use { files ->
            for (f in files) {
                val name = f.fileName.toString()
                if (!name.endsWith(".dat")) continue
                val c = SimpleContainer(54)
                runCatching {
                    val tag = NbtIo.readCompressed(f, NbtAccounter.unlimitedHeap())
                    for (e in tag.getListOrEmpty("items")) {
                        val ct = e as? CompoundTag ?: continue
                        val slot = ct.getIntOr("slot", -1)
                        val item = ct.get("item") ?: continue
                        ItemStack.CODEC.parse(ops, item).result().ifPresent { if (slot in 0 until 54) c.setItem(slot, it) }
                    }
                }
                containers[name.removeSuffix(".dat")] = c
            }
        }
        val m = d.resolve("meta.json")
        if (Files.exists(m)) runCatching { meta = gson.fromJson(Files.readString(m), Meta::class.java) }
    }

    fun register() {
        ServerLifecycleEvents.SERVER_STARTED.register { s -> load(s) }
        ServerLifecycleEvents.SERVER_STOPPING.register { s -> save(s) }
    }
}
